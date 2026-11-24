package de.foodfinder.data.sources

import de.foodfinder.data.Deal
import de.foodfinder.data.DealKind
import de.foodfinder.data.DealParser
import de.foodfinder.data.Http
import de.foodfinder.data.MenuItem
import de.foodfinder.data.Mode
import de.foodfinder.data.Service
import de.foodfinder.data.Venue
import de.foodfinder.data.arr
import de.foodfinder.data.bool
import de.foodfinder.data.dbl
import de.foodfinder.data.forEachString
import de.foodfinder.data.get
import de.foodfinder.data.haversineM
import de.foodfinder.data.int
import de.foodfinder.data.obj
import de.foodfinder.data.parseEuro
import de.foodfinder.data.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

data class Where(val lat: Double, val lon: Double, val city: String?)

interface Source {
    val service: Service
    suspend fun list(mode: Mode, at: Where): List<Venue>
    suspend fun menu(v: Venue): List<MenuItem> = emptyList()
    /** Optional second request with venue-level details (fees, banners). */
    suspend fun details(v: Venue): Venue? = null
}

private val groceryWords = listOf("lebensmittel", "geschäfte", "geschaefte", "shops", "groceries", "supermarkt", "grocery", "retail", "kiosk", "getränke", "drogerie", "apotheke", "blumen")

// ---------------------------------------------------------------- Wolt
object WoltSource : Source {
    override val service = Service.WOLT

    override suspend fun list(mode: Mode, at: Where): List<Venue> {
        val j = Http.parse(Http.get("https://consumer-api.wolt.com/v1/pages/restaurants?lat=${at.lat}&lon=${at.lon}"))
        val city = j["city"].str ?: "berlin"
        val items = j["sections"].arr.flatMap { it["items"].arr }.filter { it["venue"] != null }
        return items.mapNotNull { it -> parseVenue(it, mode, city, at) }.distinctBy { it.id }
    }

    private fun parseVenue(item: JsonElement, mode: Mode, city: String, at: Where): Venue? {
        val v = item["venue"] ?: return null
        val slug = v["slug"].str ?: return null
        val sort = item["sorting"]["sortables"].arr.associate { (it["id"].str ?: "") to it["value"].dbl }
        val range = v["estimate_range"].str?.split("-")?.mapNotNull { it.trim().toIntOrNull() }
        val loc = v["location"].arr
        val lat = loc.getOrNull(1).dbl; val lon = loc.getOrNull(0).dbl
        val deals = mutableListOf<Deal>()
        (v["promotions"].arr + v["badges"].arr + v["badges_v2"].arr).forEach { p ->
            p.forEachString({ k, s -> if (k in setOf("text", "title", "description", "name")) DealParser.parse(s)?.let { if (it.kind != DealKind.OTHER) deals += it } })
        }
        val productLine = v["product_line"].str ?: "restaurant"
        val preview = v["venue_preview_items"].arr.mapNotNull { pi ->
            val price = pi["price"].dbl ?: return@mapNotNull null
            MenuItem(pi["name"].str ?: "", price / 100.0)
        }
        val tags = v["tags"].arr.mapNotNull { it.str }
        return Venue(
            id = v["id"].str ?: slug, service = service, mode = mode, name = v["name"].str ?: slug,
            cuisines = tags,
            etaMin = if (mode == Mode.DELIVERY) range?.getOrNull(0) else null,
            etaMax = if (mode == Mode.DELIVERY) range?.getOrNull(1) else null,
            deliveryFee = null, // list only carries a sort rank; real fee comes from details()
            minOrder = null,
            rating = v["rating"]["score"].dbl?.let { it / 2.0 },
            ratingCount = v["rating"]["volume"].int?.toString(),
            distanceM = if (lat != null && lon != null) haversineM(at.lat, at.lon, lat, lon) else sort["distance"]?.toInt(),
            priceLevel = v["price_range"].int,
            deals = deals.distinctBy { it.label },
            previewItems = preview,
            url = "https://wolt.com/de/deu/$city/${if (productLine == "restaurant") "restaurant" else "venue"}/$slug",
            lat = lat, lon = lon,
            isOpen = v["online"].bool != false && (mode == Mode.PICKUP || v["delivers"].bool != false),
            isGrocery = productLine != "restaurant" || tags.any { t -> groceryWords.any { t.contains(it) } },
            plusEligible = v["show_wolt_plus"].bool,
            address = v["address"].str,
            key = slug,
        )
    }

    override suspend fun details(v: Venue): Venue? {
        val method = if (v.mode == Mode.DELIVERY) "homedelivery" else "takeaway"
        val j = Http.parse(Http.get("https://consumer-api.wolt.com/order-xp/web/v1/venue/slug/${v.key}/dynamic/?lat=${v.lat ?: 0}&lon=${v.lon ?: 0}&selected_delivery_method=$method",
            mapOf("Accept-Language" to "de-DE,de;q=0.9", "App-Language" to "de")))
        val venue = j["venue"]
        val deals = venue["banners"].arr.mapNotNull { b -> b["discount"]["formatted_text"].str?.let { t ->
            // "select at checkout" banners are account offers shown to everyone not logged in (often new-customer only)
            if (t.contains("Checkout", true)) Deal(DealKind.OTHER, t.substringBefore(":").trim() + " (Konto-Angebot, evtl. nur Neukunden)")
            else DealParser.parse(t)
        } }.filter { it.kind != DealKind.OTHER || it.label.contains("Konto-Angebot") }
        val meanings = HashMap<String, String>()
        fun walk(e: JsonElement?) {
            when (e) {
                is JsonObject -> { val m = e["meaning"].str; val value = e["value"].str; if (m != null && value != null) meanings.putIfAbsent(m, value); e.values.forEach(::walk) }
                is kotlinx.serialization.json.JsonArray -> e.forEach(::walk)
                else -> {}
            }
        }
        walk(venue)
        val fee = meanings["DELIVERY_FEE"]?.let(::parseEuro)
        val minOrder = j["order_minimum"]["amount"].dbl?.let { it / 100.0 } ?: meanings["ORDER_MINIMUM"]?.let(::parseEuro)
        return v.copy(
            deals = (v.deals + deals).distinctBy { it.label },
            deliveryFee = if (v.mode == Mode.DELIVERY) fee else null,
            minOrder = if (v.mode == Mode.DELIVERY) minOrder ?: v.minOrder else null,
            plusEligible = venue["show_wolt_plus"].bool ?: v.plusEligible,
        )
    }

    override suspend fun menu(v: Venue): List<MenuItem> {
        val j = Http.parse(Http.get("https://consumer-api.wolt.com/consumer-api/consumer-assortment/v1/venues/slug/${v.key}/assortment"))
        val cats = j["categories"].arr.flatMap { c -> c["item_ids"].arr.mapNotNull { it.str }.map { it to (c["name"].str ?: "") } }.toMap()
        return j["items"].arr.mapNotNull { i ->
            val price = i["price"].dbl ?: return@mapNotNull null
            val orig = i["original_price"].dbl
            val labels = buildList {
                i["dietary_preferences"].forEachString({ _, t -> add(t) })
                i["tags"].arr.forEach { t -> (t["label"].str ?: t["id"].str)?.let(::add) }
            }.filter { it.contains("veg", true) }
            MenuItem(i["name"].str ?: "", price / 100.0, orig?.let { it / 100.0 }, i["description"].str ?: "", cats[i["id"].str] ?: "", labels)
        }
    }
}

// ---------------------------------------------------------------- Lieferando
object LieferandoSource : Source {
    override val service = Service.LIEFERANDO
    private const val BASE = "https://rest.api.eu-central-1.production.jet-external.com"

    override suspend fun list(mode: Mode, at: Where): List<Venue> {
        val st = if (mode == Mode.DELIVERY) "delivery" else "collection"
        val j = Http.parse(Http.get("$BASE/discovery/de/restaurants/enriched?latitude=${at.lat}&longitude=${at.lon}&serviceType=$st",
            mapOf("Accept" to "application/json")))
        val fees = j["deliveryFees"]["restaurants"].obj
        // campaign filters (e.g. "2 für 1 Deals", "-50 % Deals") map onto restaurant ids
        val campaigns = HashMap<String, MutableList<String>>()
        var plusIds: Set<String>? = null
        j["filters"].obj?.forEach { (key, f) ->
            val name = f["displayName"].str ?: return@forEach
            val ids = f["restaurantIds"].arr.mapNotNull { it.str }
            if (key.contains("plus", true) || name.contains("Lieferando+") || name.contains("Plus", false)) {
                if (ids.isNotEmpty()) plusIds = (plusIds ?: emptySet()) + ids
                return@forEach
            }
            if (f["group"].str == "cuisine" || ids.isEmpty()) return@forEach
            if (Regex("neukunde|für dich|dein |gutschein|partner|essentials|neu$", RegexOption.IGNORE_CASE).containsMatchIn(name)) return@forEach
            if (!Regex("%|€|für 1|gratis|deal", RegexOption.IGNORE_CASE).containsMatchIn(name)) return@forEach
            ids.forEach { campaigns.getOrPut(it) { mutableListOf() } += name }
        }
        return j["restaurants"].arr.mapNotNull { r ->
            val id = r["id"].str ?: return@mapNotNull null
            val slug = r["uniqueName"].str ?: return@mapNotNull null
            val fee = fees?.get(id)
            val bands = fee["bands"].arr
            val minOrder = fee["minimumOrderValue"].dbl?.let { it / 100.0 } ?: r["minimumDeliveryValue"].dbl
            val deliveryFee = bands.firstOrNull()?.get("fee").dbl?.let { it / 100.0 } ?: r["deliveryCost"].dbl
            val deals = r["deals"].arr.mapNotNull { d -> DealParser.parse(d["description"].str ?: "", offerType = d["offerType"].str) }
                .plus(campaigns[id].orEmpty().mapNotNull { name ->
                    DealParser.parse(name)?.let { d -> if (d.kind == DealKind.ORDER_PERCENT) d.copy(kind = DealKind.ITEM_PERCENT) else d }
                })
                .filter { it.kind != DealKind.OTHER || it.label.isNotBlank() }
                .distinctBy { it.label }
            val cuisines = r["cuisines"].arr.mapNotNull { it["name"].str }.filter { it != "Angebote" }.distinct()
            val coords = r["address"]["location"]["coordinates"].arr
            val eta = r["deliveryEtaMinutes"]
            val open = if (mode == Mode.DELIVERY) r["isOpenNowForDelivery"].bool != false else r["isOpenNowForCollection"].bool != false
            Venue(
                id = id, service = service, mode = mode, name = r["name"].str ?: slug,
                cuisines = cuisines,
                etaMin = if (mode == Mode.DELIVERY) eta["rangeLower"].int else null,
                etaMax = if (mode == Mode.DELIVERY) eta["rangeUpper"].int else null,
                deliveryFee = if (mode == Mode.DELIVERY) deliveryFee else null,
                minOrder = if (mode == Mode.DELIVERY) minOrder else null,
                rating = r["rating"]["starRating"].dbl, ratingCount = r["rating"]["count"].int?.toString(),
                distanceM = r["driveDistanceMeters"].int,
                deals = deals,
                url = "https://www.lieferando.de/speisekarte/$slug",
                lat = coords.getOrNull(1).dbl, lon = coords.getOrNull(0).dbl,
                isOpen = open && r["isTemporarilyOffline"].bool != true,
                isGrocery = r["cuisines"].arr.any { c -> groceryWords.any { (c["uniqueName"].str ?: "").contains(it) } },
                plusEligible = plusIds?.contains(id),
                address = listOfNotNull(r["address"]["firstLine"].str, r["address"]["city"].str).joinToString(", "),
                key = slug,
            )
        }
    }

    override suspend fun menu(v: Venue): List<MenuItem> {
        // small CDN file (~100 KB) first; the full menu page (~2 MB) only as fallback
        runCatching {
            val j = Http.parse(Http.get("https://globalmenucdn.eu-central-1.production.jet-external.com/${v.key}_de_items.json"))
            val items = j["Items"].arr.mapNotNull { it ->
                val price = it["Variations"].arr.firstOrNull()?.get("BasePrice").dbl ?: return@mapNotNull null
                MenuItem(it["Name"].str ?: "", price, null, it["Description"].str ?: "", "", it["Labels"].arr.mapNotNull { l -> l.str })
            }
            if (items.isNotEmpty()) return items
        }
        val html = Http.get(v.url)
        val m = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html) ?: return emptyList()
        val j = Http.parse(m.groupValues[1])
        val r = j["props"]["appProps"]["preloadedState"]["menu"]["restaurant"]
        val items = r["cdn"]["items"].obj ?: return emptyList()
        return items.values.mapNotNull { it ->
            val first = it["variations"].arr.firstOrNull() ?: return@mapNotNull null
            val price = first["basePrice"].dbl ?: return@mapNotNull null
            MenuItem(it["name"].str ?: "", price, null, it["description"].str ?: "", "", it["labels"].arr.mapNotNull { l -> l.str })
        }
    }
}

// ---------------------------------------------------------------- Uber Eats
object UberSource : Source {
    override val service = Service.UBER_EATS

    private fun locCookie(at: Where): String {
        val js = """{"address":{"address1":"${at.city ?: "Standort"}","city":"${at.city ?: ""}","country":"DE"},"latitude":${at.lat},"longitude":${at.lon},"reference":"ff","referenceType":"google_places","type":"google_places"}"""
        return "uev2.loc=" + URLEncoder.encode(js, "UTF-8").replace("+", "%20")
    }

    private fun headers(at: Where) = mapOf("x-csrf-token" to "x", "Cookie" to locCookie(at), "Origin" to "https://www.ubereats.com")

    override suspend fun list(mode: Mode, at: Where): List<Venue> {
        val dm = if (mode == Mode.DELIVERY) "DELIVERY" else "PICKUP"
        val body = """{"diningMode":"$dm","cacheKey":"","feedSessionCount":{"announcementCount":0,"announcementLabel":""},"userQuery":"","date":"","startTime":0,"endTime":0,"carouselId":"","sortAndFilters":[],"billboardUuid":"","feedProvider":"","promotionUuid":"","targetingStoreTag":"","venueUUID":"","selectedSectionUUID":"","favorites":"","vertical":"","searchSource":"","searchType":"","keyName":"","serializedRequestContext":"","isUserInitiatedRefresh":false}"""
        val (code, text) = Http.post("https://www.ubereats.com/_p/api/getFeedV1?localeCode=de", body, headers(at))
        if (code != 200) throw Http.HttpError(code, "HTTP $code")
        val d = Http.parse(text)["data"]
        val stores = mutableListOf<JsonElement>()
        d["feedItems"].arr.forEach { f ->
            f["store"]?.let { stores += it }
            f["carousel"]["stores"].arr.forEach { stores += it }
            f["stores"].arr.forEach { stores += it }
        }
        return stores.mapNotNull { s -> parseStore(s, mode, at) }.distinctBy { it.id }
    }

    private fun parseStore(s: JsonElement, mode: Mode, at: Where): Venue? {
        val id = s["storeUuid"].str ?: return null
        val name = s["title"]["text"].str ?: return null
        var fee: Double? = null; var etaMin: Int? = null
        s["meta"].arr.forEach { m ->
            when (m["badgeType"].str) {
                "FARE" -> fee = parseEuro(m["badgeData"]["fare"]["deliveryFee"].str ?: m["text"].str)
                "ETD" -> etaMin = m["text"].str?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
            }
        }
        val etd = s["tracking"]["storePayload"]["etdInfo"]["dropoffETARange"]
        val deals = s["signposts"].arr.mapNotNull { DealParser.parse(it["text"].str ?: "") }
        val lat = s["mapMarker"]["latitude"].dbl; val lon = s["mapMarker"]["longitude"].dbl
        val action = s["actionUrl"].str ?: "/store/$id"
        val payload = s["tracking"]["storePayload"]
        val avail = payload["storeAvailablityState"].str
        return Venue(
            id = id, service = service, mode = mode, name = name,
            cuisines = emptyList(), // the feed carries no reliable cuisine tags
            etaMin = if (mode == Mode.DELIVERY) etd["min"].int ?: etaMin else null,
            etaMax = if (mode == Mode.DELIVERY) etd["max"].int else null,
            deliveryFee = if (mode == Mode.DELIVERY) fee else null,
            rating = s["rating"]["text"].str?.replace(',', '.')?.toDoubleOrNull(),
            ratingCount = payload["ratingInfo"]["ratingCount"].str,
            distanceM = if (lat != null && lon != null) haversineM(at.lat, at.lon, lat, lon) else null,
            deals = deals.distinctBy { it.label },
            url = "https://www.ubereats.com" + action.substringBefore("?") + "?diningMode=" + (if (mode == Mode.DELIVERY) "DELIVERY" else "PICKUP"),
            lat = lat, lon = lon,
            isOpen = avail == null || avail == "ACCEPTING_ORDERS" || payload["isOrderable"].bool == true,
            isGrocery = name.contains("REWE", true) || name.contains("Flink", true) || name.contains("Kiosk", true) || name.contains("Markt", true),
            key = id,
        )
    }

    override suspend fun menu(v: Venue): List<MenuItem> {
        val at = Where(v.lat ?: 52.52, v.lon ?: 13.4, null)
        val dm = if (v.mode == Mode.DELIVERY) "DELIVERY" else "PICKUP"
        val (code, text) = Http.post("https://www.ubereats.com/_p/api/getStoreV1?localeCode=de",
            """{"storeUuid":"${v.id}","diningMode":"$dm","time":{"asap":true},"cbType":"EATER_ENDORSED"}""", headers(at))
        if (code != 200) throw Http.HttpError(code, "HTTP $code")
        val d = Http.parse(text)["data"]
        val out = mutableListOf<MenuItem>()
        d["catalogSectionsMap"].obj?.values?.forEach { secs ->
            secs.arr.forEach { sec ->
                val payload = sec["payload"]["standardItemsPayload"]
                val cat = payload["title"]["text"].str ?: ""
                payload["catalogItems"].arr.forEach { it ->
                    val price = it["price"].dbl ?: return@forEach
                    val acc = it["priceTagline"]["accessibilityText"].str ?: ""
                    val fmt = it["priceTagline"]["textFormat"].str ?: ""
                    val struck = Regex("""line-through[^>]*>\s*([\d.,]+)\s?€""").find(fmt)?.groupValues?.get(1)
                    val orig0 = Regex("""(?:discounted from|statt|reduziert von)\s*([\d.,]+)\s?€""").find(acc)?.groupValues?.get(1)
                        ?.replace(".", "")?.replace(',', '.')?.toDoubleOrNull()
                    val orig = orig0 ?: struck?.replace(".", "")?.replace(',', '.')?.toDoubleOrNull()
                    val badge = it["titleBadge"]["accessibilityText"].str ?: ""
                    val labels = listOfNotNull("VEGAN".takeIf { badge.contains("VEGAN") }, "VEGETARIAN".takeIf { badge.contains("VEGETARIAN") })
                    out += MenuItem(it["title"].str ?: "", price / 100.0, orig, it["itemDescription"].str ?: "", cat, labels)
                }
            }
        }
        return out.distinctBy { it.name + it.price }
    }
}

// ---------------------------------------------------------------- NeoTaste
object NeoTasteSource : Source {
    override val service = Service.NEOTASTE

    override suspend fun list(mode: Mode, at: Where): List<Venue> {
        if (mode != Mode.PICKUP) return emptyList()
        val city = de.foodfinder.data.slugify(at.city ?: return emptyList())
        val all = LinkedHashMap<String, Venue>()
        for (page in 1..4) {
            val html = try {
                Http.get("https://neotaste.com/de/restaurants/$city" + if (page > 1) "?page=$page" else "")
            } catch (e: Http.HttpError) { if (page == 1) throw e else break }
            val before = all.size
            parse(html, at).forEach { all.putIfAbsent(it.id, it) }
            if (all.size == before) break
        }
        return all.values.toList()
    }

    private fun parse(html: String, at: Where): List<Venue> {
        val chunks = nextChunks(html)
        val out = mutableListOf<Venue>()
        // locate JSON arrays of restaurants: objects that carry "deals" and "latitude"
        var idx = chunks.indexOf("\"redeemedBookingsCount\"")
        val seen = HashSet<Int>()
        while (idx >= 0) {
            val start = chunks.lastIndexOf("{\"cached\"", idx).takeIf { it >= 0 } ?: chunks.lastIndexOf("{\"uuid\"", idx)
            if (start >= 0 && seen.add(start)) {
                val end = matchBrace(chunks, start)
                if (end > start) {
                    try { toVenue(Http.parse(chunks.substring(start, end + 1)), at)?.let { out += it } } catch (_: Exception) {}
                }
            }
            idx = chunks.indexOf("\"redeemedBookingsCount\"", idx + 10)
        }
        return out
    }

    /** Concatenate the Next.js flight payload strings without a (stack-hungry) regex. */
    private fun nextChunks(html: String): String {
        val sb = StringBuilder()
        val marker = "self.__next_f.push([1,\""
        var i = html.indexOf(marker)
        while (i >= 0) {
            var j = i + marker.length
            val raw = StringBuilder()
            while (j < html.length) {
                val c = html[j]
                if (c == '\\' && j + 1 < html.length) { raw.append(c).append(html[j + 1]); j += 2; continue }
                if (c == '"') break
                raw.append(c); j++
            }
            runCatching { sb.append(Http.json.decodeFromString<String>("\"" + raw + "\"")) }
            i = html.indexOf(marker, j)
        }
        return sb.toString()
    }

    private fun matchBrace(s: String, start: Int): Int {
        var depth = 0; var inStr = false; var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) return i } }
        }
        return -1
    }

    private fun toVenue(r: JsonElement, at: Where): Venue? {
        val uuid = r["uuid"].str ?: return null
        val lat = r["latitude"].dbl; val lon = r["longitude"].dbl
        val deals = r["deals"].arr.filter { it["status"].str != "unavailable" }.mapNotNull { d ->
            val name = d["name"].str ?: return@mapNotNull null
            val value = d["value"].dbl
            val where = if (d["locationCondition"].str == "onSite") " · vor Ort" else " · Abholung"
            Deal(DealKind.FIXED_VALUE, name + where, amount = value)
        }
        val dealText = r["deals"].arr.joinToString(" ") { (it["name"].str ?: "") + " " + (it["conditions"].str ?: "") }
        val dietTags = listOfNotNull("VEGAN".takeIf { dealText.contains("vegan", true) },
            "VEGETARIAN".takeIf { Regex("(?i)vegetar|veggie|vegan").containsMatchIn(dealText) })
        if (deals.isEmpty()) return null
        val city = r["citySlug"].str ?: "berlin"
        return Venue(
            id = uuid, service = service, mode = Mode.PICKUP, name = r["name"].str ?: "",
            cuisines = r["tags"].arr.filter { it["category"].str == "cuisine" }.mapNotNull { it["name"].str },
            rating = r["avgRating"].dbl, ratingCount = r["ratingsCount"].int?.toString(),
            distanceM = if (lat != null && lon != null) haversineM(at.lat, at.lon, lat, lon) else null,
            priceLevel = r["priceRange"].int, deals = deals,
            url = "https://neotaste.com/de/restaurants/$city/${r["slug"].str}",
            lat = lat, lon = lon, address = r["neighborhood"].str, key = r["slug"].str ?: uuid, dietTags = dietTags,
            isOpen = r["isActive"].bool != false,
        )
    }
}

// ---------------------------------------------------------------- Too Good To Go (unofficial app API, needs login)
class TgtgSource(private val auth: TgtgAuth) : Source {
    override val service = Service.TGTG

    override suspend fun list(mode: Mode, at: Where): List<Venue> {
        if (mode != Mode.PICKUP) return emptyList()
        val token = auth.validAccessToken() ?: throw IllegalStateException("Nicht angemeldet")
        val body = """{"user_id":"${auth.userId ?: ""}","origin":{"latitude":${at.lat},"longitude":${at.lon}},"radius":5,"page_size":100,"page":1,"discover":false,"favorites_only":false,"item_categories":[],"diet_categories":[],"pickup_earliest":null,"pickup_latest":null,"search_phrase":null,"with_stock_only":true,"hidden_only":false,"we_care_only":false}"""
        val (code, text) = Http.post(TgtgAuth.BASE + "item/v8/", body, auth.headers(token))
        if (code == 401) { auth.invalidate(); throw IllegalStateException("Anmeldung abgelaufen") }
        if (code != 200) throw Http.HttpError(code, if (code == 403) "TGTG sperrt Dritt-Apps gerade (Bot-Schutz)" else "HTTP $code")
        return Http.parse(text)["items"].arr.mapNotNull { e ->
            val item = e["item"]
            val id = item["item_id"].str ?: return@mapNotNull null
            fun money(x: JsonElement?): Double? {
                val minor = x["minor_units"].dbl ?: return null
                val dec = x["decimals"].int ?: 2
                return minor / Math.pow(10.0, dec.toDouble())
            }
            val price = money(item["item_price"]) ?: money(item["price_including_taxes"])
            val value = money(item["item_value"]) ?: money(item["value_including_taxes"])
            val store = e["store"]
            val loc = store["store_location"]["location"]
            val start = e["pickup_interval"]["start"].str
            val end = e["pickup_interval"]["end"].str
            Venue(
                id = id, service = service, mode = Mode.PICKUP,
                name = e["display_name"].str ?: store["store_name"].str ?: "",
                cuisines = listOfNotNull(item["item_category"].str?.lowercase()?.replaceFirstChar { it.uppercase() }, item["name"].str?.takeIf { it.isNotBlank() }),
                rating = item["average_overall_rating"]["average_overall_rating"].dbl,
                distanceM = e["distance"].dbl?.let { (it * 1000).toInt() },
                url = "https://share.toogoodtogo.com/item/$id",
                lat = loc["latitude"].dbl, lon = loc["longitude"].dbl,
                isOpen = (e["items_available"].int ?: 0) > 0,
                pickupWindow = if (start != null && end != null) fmtWindow(start, end) else null,
                itemsAvailable = e["items_available"].int,
                bagPrice = price, bagValue = value,
                address = store["store_location"]["address"]["address_line"].str,
                key = id,
                deals = listOf(Deal(DealKind.SURPRISE_BAG, "Überraschungstüte")),
                dietTags = item["diet_categories"].arr.mapNotNull { it.str },
            )
        }
    }

    private fun fmtWindow(s: String, e: String): String {
        fun hm(x: String) = try {
            java.time.ZonedDateTime.parse(x).withZoneSameInstant(java.time.ZoneId.systemDefault()).let { "%02d:%02d".format(it.hour, it.minute) }
        } catch (_: Exception) { x.take(16) }
        return "${hm(s)}–${hm(e)}"
    }
}

class TgtgAuth(private val store: de.foodfinder.data.Prefs) {
    companion object { const val BASE = "https://apptoogoodtogo.com/api/" }

    val userId: String? get() = store.tgtgUserId
    val loggedIn: Boolean get() = store.tgtgRefresh != null

    fun headers(token: String? = null) = buildMap {
        put("User-Agent", "TGTG/${store.tgtgAppVersion} Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/AP2A.240805.005)")
        put("Accept", "application/json")
        put("Accept-Language", "de-DE")
        if (token != null) put("Authorization", "Bearer $token")
    }

    fun invalidate() { store.tgtgAccess = null }

    /** Step 1: request login mail. Returns polling id. */
    suspend fun requestLogin(email: String): String {
        val (code, text) = Http.post(BASE + "auth/v5/authByEmail", """{"device_type":"ANDROID","email":"$email"}""", headers())
        if (code != 200) throw Http.HttpError(code, if (code == 403) "Too Good To Go sperrt gerade Zugriffe von Dritt-Apps (Bot-Schutz). FoodFinder umgeht das nicht. Tüten aus TGTG-Benachrichtigungen erscheinen trotzdem unter „Abholen“." else "HTTP $code")
        val j = Http.parse(text)
        if (j["state"].str == "TERMS") throw IllegalStateException("Für diese E-Mail gibt es noch kein TGTG-Konto")
        store.tgtgEmail = email
        return j["polling_id"].str ?: throw IllegalStateException("Keine Polling-ID erhalten")
    }

    /** Step 2: poll after user confirmed the link, or with the PIN from the mail. */
    suspend fun complete(pollingId: String, pin: String?): Boolean {
        val email = store.tgtgEmail ?: return false
        val (code, text) = if (!pin.isNullOrBlank())
            Http.post(BASE + "auth/v5/authByRequestPin", """{"device_type":"ANDROID","email":"$email","request_pin":"${pin.trim()}","request_polling_id":"$pollingId"}""", headers())
        else Http.post(BASE + "auth/v5/authByRequestPollingId", """{"device_type":"ANDROID","email":"$email","request_polling_id":"$pollingId"}""", headers())
        if (code == 202) return false
        if (code != 200) throw Http.HttpError(code, "HTTP $code")
        val j = Http.parse(text)
        store.tgtgAccess = j["access_token"].str
        store.tgtgRefresh = j["refresh_token"].str
        store.tgtgUserId = j["startup_data"]["user"]["user_id"].str ?: j["user"]["user_id"].str
        store.tgtgAccessAt = System.currentTimeMillis()
        return store.tgtgAccess != null
    }

    suspend fun validAccessToken(): String? {
        val acc = store.tgtgAccess
        if (acc != null && System.currentTimeMillis() - store.tgtgAccessAt < 3 * 3600_000L) return acc
        val refresh = store.tgtgRefresh ?: return null
        val (code, text) = Http.post(BASE + "token/v1/refresh", """{"refresh_token":"$refresh"}""", headers())
        if (code != 200) return acc
        val j = Http.parse(text)
        store.tgtgAccess = j["access_token"].str
        j["refresh_token"].str?.let { store.tgtgRefresh = it }
        store.tgtgAccessAt = System.currentTimeMillis()
        return store.tgtgAccess
    }

    fun logout() { store.tgtgAccess = null; store.tgtgRefresh = null; store.tgtgUserId = null }
}

@Suppress("unused") private val keepJsonObject: JsonObject? = null
