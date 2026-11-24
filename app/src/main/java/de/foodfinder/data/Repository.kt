package de.foodfinder.data

import android.content.Context
import de.foodfinder.data.sources.LieferandoSource
import de.foodfinder.data.sources.NeoTasteSource
import de.foodfinder.data.sources.Source
import de.foodfinder.data.sources.TgtgAuth
import de.foodfinder.data.sources.TgtgSource
import de.foodfinder.data.sources.UberSource
import de.foodfinder.data.sources.WoltSource
import de.foodfinder.data.sources.Where
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

class Repository(ctx: Context) {
    val prefs = Prefs(ctx)
    val locator = Locator(ctx)
    val tgtgAuth = TgtgAuth(prefs)

    private val sources: Map<Service, Source> = mapOf(
        Service.LIEFERANDO to LieferandoSource,
        Service.UBER_EATS to UberSource,
        Service.WOLT to WoltSource,
        Service.NEOTASTE to NeoTasteSource,
        Service.TGTG to TgtgSource(tgtgAuth),
    )

    private val menuCache = ConcurrentHashMap<String, Pair<Long, List<MenuItem>>>()
    private val detailCache = ConcurrentHashMap<String, Pair<Long, Venue>>()

    /** Venue with whatever the detail request added (fees, banner deals). */
    fun merged(v: Venue): Venue = detailCache[v.uid]?.takeIf { System.currentTimeMillis() - it.first < 20 * 60_000 }?.second ?: v

    data class LoadResult(val venues: List<Venue>, val status: List<SourceStatus>, val at: Where)

    /** Resolve where we are: active saved place, else GPS, else last known. */
    suspend fun where(useGps: Boolean = true): Where? {
        prefs.activePlaceId?.let { id ->
            prefs.places.firstOrNull { it.id == id }?.let { p ->
                val city = prefs.lastCity.takeIf { prefs.lastPlaceLabel == p.name } ?: locator.reverse(p.lat, p.lon).first
                prefs.lastPlaceLabel = p.name
                prefs.lastCity = city
                return Where(p.lat, p.lon, city)
            }
        }
        if (useGps) {
            locator.current()?.let { loc ->
                val (city, label) = locator.reverse(loc.latitude, loc.longitude)
                prefs.lastLat = loc.latitude; prefs.lastLon = loc.longitude
                prefs.lastCity = city; prefs.lastPlaceLabel = label ?: "Aktueller Standort"
                return Where(loc.latitude, loc.longitude, city)
            }
        }
        val lat = prefs.lastLat ?: return null
        val lon = prefs.lastLon ?: return null
        return Where(lat, lon, prefs.lastCity)
    }

    suspend fun load(mode: Mode, at: Where, services: Set<Service>): LoadResult = coroutineScope {
        val wanted = mode.servicesFor.filter { it in services }
        val jobs = wanted.map { s ->
            async {
                if (s == Service.TGTG && !tgtgAuth.loggedIn) return@async s to kotlin.Result.failure<List<Venue>>(IllegalStateException("Nicht angemeldet – in den Einstellungen verbinden"))
                if (s == Service.NEOTASTE && at.city == null) return@async s to kotlin.Result.failure<List<Venue>>(IllegalStateException("Stadt unbekannt"))
                s to runCatching { withTimeout(35_000) { sources.getValue(s).list(mode, at) } }
            }
        }
        val results = jobs.awaitAll().map { (s, r) ->
            // Too Good To Go blocks third-party apps at times; fall back to the bags its own app announced by push
            if (s == Service.TGTG && r.isFailure) {
                val push = tgtgFromPushes(at)
                if (push.isNotEmpty()) s to kotlin.Result.success(push) else s to r
            } else s to r
        }
        val venues = results.flatMap { it.second.getOrDefault(emptyList()) }
        val status = results.map { (s, r) ->
            SourceStatus(s, r.isSuccess, r.getOrNull()?.size ?: 0, r.exceptionOrNull()?.let(::friendly))
        }
        LoadResult(venues, status, at)
    }

    private fun tgtgFromPushes(at: Where): List<Venue> {
        val since = System.currentTimeMillis() - 6 * 3600_000L
        return prefs.tgtgPushes.filter { it.at > since }.map { n ->
            Venue(
                id = "push-${n.at}", service = Service.TGTG, mode = Mode.PICKUP,
                name = n.title.ifBlank { "Too Good To Go" },
                deals = listOf(Deal(DealKind.OTHER, n.text.take(140), origin = DealOrigin.PUSH)),
                url = "https://www.toogoodtogo.com/de",
                pickupWindow = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY).format(n.at).let { "gemeldet $it" },
                key = "push",
            )
        }
    }

    private fun friendly(e: Throwable): String = when (e) {
        is Http.HttpError -> when (e.code) {
            403, 429 -> e.message?.takeIf { it.length > 12 } ?: "Vom Dienst blockiert (${e.code}), später erneut"
            404 -> "Nicht verfügbar an diesem Ort"
            else -> e.message ?: "Fehler"
        }
        is java.net.UnknownHostException -> "Keine Internetverbindung"
        is kotlinx.coroutines.TimeoutCancellationException -> "Zeitüberschreitung"
        else -> e.message ?: e.javaClass.simpleName
    }

    fun cachedMenu(v: Venue): List<MenuItem>? = menuCache[v.uid]?.takeIf { System.currentTimeMillis() - it.first < 20 * 60_000 }?.second

    suspend fun details(v: Venue): Venue {
        detailCache[v.uid]?.takeIf { System.currentTimeMillis() - it.first < 20 * 60_000 }?.let { return it.second }
        val d = runCatching { sources.getValue(v.service).details(v) }.getOrNull() ?: v
        detailCache[v.uid] = System.currentTimeMillis() to d
        return d
    }

    suspend fun menu(v: Venue): List<MenuItem> {
        cachedMenu(v)?.let { return it }
        val items = sources.getValue(v.service).menu(v)
        menuCache[v.uid] = System.currentTimeMillis() to items
        return items
    }

    /** Load menus for the most promising venues so item discounts become exact. */
    suspend fun enrich(ranked: List<Venue>, limit: Int = 16, onEach: (Venue) -> Unit = {}) = coroutineScope {
        val sem = Semaphore(5)
        // per service: the current leaders, plus for Wolt (no deals in the list) the best rated nearby
        val picks = ranked.filter { it.service != Service.WOLT && it.service in setOf(Service.UBER_EATS, Service.LIEFERANDO) && !it.isGrocery }.take(limit) +
            ranked.filter { it.service == Service.WOLT && !it.isGrocery && it.isOpen }
                .sortedWith(compareByDescending<Venue> { (it.rating ?: 0.0) >= 4.2 }.thenBy { it.distanceM ?: Int.MAX_VALUE }).take(limit + 8)
        picks.filter { cachedMenu(it) == null }
            .map { v -> async { sem.withPermit {
                runCatching { withTimeout(15_000) { details(v) } }
                runCatching { withTimeout(15_000) { menu(v) } }
                onEach(v)
            } } }
            .awaitAll()
    }

    fun activeVouchers(): List<Voucher> {
        val cutoff = System.currentTimeMillis() - 21L * 24 * 3600_000
        return prefs.vouchers.filter { it.receivedAt > cutoff && !expired(it.validUntil) }
    }

    private fun expired(until: String?): Boolean {
        if (until == null) return false
        val m = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{2,4})?""").find(until) ?: return false
        val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
        val now = java.time.LocalDate.now()
        val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it } ?: now.year
        return runCatching { java.time.LocalDate.of(y, mo, d).isBefore(now) }.getOrDefault(false)
    }

    fun rank(venues: List<Venue>, persons: Int, filters: Filters, query: String): List<Ranked> {
        val membership = prefs.membership
        val vouchers = activeVouchers()
        val q = query.trim().lowercase()
        return venues.asSequence()
            .filter { it.service in filters.services }
            .filter { !filters.onlyOpen || it.isOpen }
            .filter { !filters.hideGroceries || !it.isGrocery }
            .filter { filters.cuisines.isEmpty() || it.cuisines.any { c -> filters.cuisines.any { f -> c.contains(f, true) || f.contains(c, true) } } }
            .filter { filters.maxEta == null || (it.etaMax ?: it.etaMin ?: 0) <= filters.maxEta }
            .filter { filters.maxMinOrder == null || (it.minOrder ?: 0.0) <= filters.maxMinOrder }
            .filter { filters.maxDeliveryFee == null || (it.deliveryFee ?: 0.0) <= filters.maxDeliveryFee }
            .filter { filters.minRating == null || (it.rating ?: 0.0) >= filters.minRating }
            .map { merged(it) }
            .mapNotNull { v -> dietView(v, filters.diet) }
            .filter { (v, items) -> q.isEmpty() || matches(v, q, items) }
            .map { (v, items) -> Ranked(v, Savings.estimate(v, persons, membership, vouchers, items)) }
            .filter { !filters.onlyWithDeals || it.estimate.savings > 0.01 }
            .sortedWith(
                when (filters.sort) {
                    SortBy.SAVINGS -> compareByDescending<Ranked> { it.estimate.score }.thenBy { it.estimate.total }
                    SortBy.TOTAL -> compareBy { it.estimate.total }
                    SortBy.ETA -> compareBy { it.venue.etaMin ?: 999 }
                    SortBy.DISTANCE -> compareBy { it.venue.distanceM ?: Int.MAX_VALUE }
                    SortBy.RATING -> compareByDescending { it.venue.rating ?: 0.0 }
                }
            )
            .toList()
    }

    /**
     * With a diet filter only dishes count, not the restaurant's type: a venue stays only if its checked menu
     * has matching main dishes, and the estimate is then built from those dishes.
     * Returns the venue plus the menu items to calculate with (null = menu not loaded, estimate as usual).
     */
    private fun dietView(v: Venue, diet: Diet): Pair<Venue, List<MenuItem>?>? {
        val menu = cachedMenu(v)
        if (diet == Diet.ANY) return v to menu
        return when (v.service) {
            Service.TGTG, Service.NEOTASTE -> if (DietCheck.tagsMatch(v.dietTags, diet)) v to null else null
            else -> {
                val dishes = menu?.filter { DietCheck.matches(it, diet) } ?: return null
                if (dishes.none(Savings::isMain)) null else v to dishes
            }
        }
    }

    fun checkedMenus(venues: List<Venue>) = venues.count { cachedMenu(it) != null }

    private fun matches(v: Venue, q: String, items: List<MenuItem>?): Boolean {
        val words = Synonyms.expand(q)
        val hay = buildString {
            append(v.name.lowercase()); append(' ')
            v.cuisines.forEach { append(it.lowercase()); append(' ') }
            v.previewItems.forEach { append(it.name.lowercase()); append(' ') }
            (items ?: cachedMenu(v))?.take(300)?.forEach { append(it.name.lowercase()); append(' ') }
        }
        return words.any { hay.contains(it) }
    }
}

/** Search suggestions & cuisine synonyms (German/English, as services label differently). */
object Synonyms {
    private val groups = listOf(
        listOf("pizza", "italienisch", "italian", "pasta"),
        listOf("burger", "american", "amerikanisch", "smash"),
        listOf("sushi", "japanisch", "japanese", "poke", "ramen"),
        listOf("asiatisch", "asian", "vietnamesisch", "vietnamese", "thai", "chinesisch", "chinese", "korean", "koreanisch", "bowl"),
        listOf("indisch", "indian", "curry"),
        listOf("döner", "doener", "kebab", "türkisch", "turkish", "dürüm", "falafel"),
        listOf("vegan", "vegetarisch", "vegetarian", "veggie"),
        listOf("frühstück", "breakfast", "brunch", "café", "cafe", "bäcker", "bakery"),
        listOf("dessert", "kuchen", "eis", "ice cream", "süß", "sweets", "donut"),
        listOf("salat", "salad", "healthy", "gesund", "bowl"),
        listOf("chicken", "hähnchen", "huhn", "wings"),
        listOf("mexikanisch", "mexican", "taco", "burrito"),
        listOf("griechisch", "greek", "gyros", "mediterran", "mediterranean"),
    )

    fun expand(q: String): List<String> {
        val base = q.lowercase().trim()
        val g = groups.firstOrNull { grp -> grp.any { it == base || base.contains(it) } }
        return (listOf(base) + (g ?: emptyList())).distinct()
    }

    fun forNow(hour: Int = java.time.LocalTime.now().hour): List<String> = when (hour) {
        in 5..10 -> listOf("Frühstück", "Bäcker", "Café", "Bowl")
        in 11..14 -> listOf("Bowl", "Burger", "Asiatisch", "Pizza", "Salat")
        in 15..17 -> listOf("Kuchen", "Eis", "Café", "Döner")
        in 18..22 -> listOf("Pizza", "Sushi", "Burger", "Indisch", "Döner")
        else -> listOf("Döner", "Burger", "Pizza", "Chicken")
    }
}
