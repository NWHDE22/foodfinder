package de.foodfinder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.foodfinder.data.Estimate
import de.foodfinder.data.Filters
import de.foodfinder.data.MenuItem
import de.foodfinder.data.Mode
import de.foodfinder.data.Ranked
import de.foodfinder.data.Repository
import de.foodfinder.data.SavedPlace
import de.foodfinder.data.Savings
import de.foodfinder.data.SourceStatus
import de.foodfinder.data.Venue
import de.foodfinder.data.sources.Where
import de.foodfinder.mail.MailScanner
import de.foodfinder.notify.DealWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModeState(
    val venues: List<Venue> = emptyList(),
    val status: List<SourceStatus> = emptyList(),
    val ranked: List<Ranked> = emptyList(),
    val loading: Boolean = false,
    val enriching: Boolean = false,
    val loadedAt: Long = 0,
    val at: Where? = null,
)

data class UiState(
    val mode: Mode = Mode.DELIVERY,
    val delivery: ModeState = ModeState(),
    val pickup: ModeState = ModeState(),
    val query: String = "",
    val filters: Filters = Filters(),
    val persons: Int = 2,
    val placeLabel: String = "Standort wird gesucht…",
    val places: List<SavedPlace> = emptyList(),
    val activePlaceId: String? = null,
    val needsLocation: Boolean = false,
    val recent: List<String> = emptyList(),
    val message: String? = null,
) {
    fun of(m: Mode) = if (m == Mode.DELIVERY) delivery else pickup
}

data class DetailState(val venue: Venue, val items: List<MenuItem>? = null, val loading: Boolean = false, val error: String? = null, val estimate: Estimate)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository(app)
    private val p = repo.prefs

    private val _state = MutableStateFlow(UiState(filters = p.filters, persons = p.persons, places = p.places, activePlaceId = p.activePlaceId, recent = p.recentSearches))
    val state: StateFlow<UiState> = _state

    private val _detail = MutableStateFlow<DetailState?>(null)
    val detail: StateFlow<DetailState?> = _detail

    private val jobs = HashMap<Mode, Job>()

    fun onLocationPermission(granted: Boolean) {
        _state.update { it.copy(needsLocation = !granted && p.activePlaceId == null) }
        refresh(Mode.DELIVERY, force = true)
    }

    fun setMode(m: Mode) {
        _state.update { it.copy(mode = m) }
        val ms = _state.value.of(m)
        if (ms.venues.isEmpty() && !ms.loading || System.currentTimeMillis() - ms.loadedAt > 10 * 60_000) refresh(m)
    }

    fun refresh(m: Mode = _state.value.mode, force: Boolean = false) {
        if (jobs[m]?.isActive == true && !force) return
        jobs[m]?.cancel()
        jobs[m] = viewModelScope.launch {
            updateMode(m) { it.copy(loading = true) }
            val at = repo.where(useGps = true)
            _state.update { it.copy(placeLabel = p.lastPlaceLabel ?: "Unbekannter Ort", needsLocation = at == null) }
            if (at == null) { updateMode(m) { it.copy(loading = false) }; return@launch }
            val res = repo.load(m, at, _state.value.filters.services)
            updateMode(m) { it.copy(venues = res.venues, status = res.status, loading = false, loadedAt = System.currentTimeMillis(), at = at) }
            rerank(m)
            // enrich top candidates with real menus so item discounts become exact
            updateMode(m) { it.copy(enriching = true) }
            var last = 0L
            repo.enrich(_state.value.of(m).ranked.map { it.venue }) {
                val now = System.currentTimeMillis()
                if (now - last > 700) { last = now; rerank(m) }
            }
            rerank(m)
            updateMode(m) { it.copy(enriching = false) }
        }
    }

    private fun updateMode(m: Mode, f: (ModeState) -> ModeState) = _state.update {
        if (m == Mode.DELIVERY) it.copy(delivery = f(it.delivery)) else it.copy(pickup = f(it.pickup))
    }

    private fun rerank(m: Mode) {
        val s = _state.value
        val ranked = repo.rank(s.of(m).venues, s.persons, s.filters, s.query)
        updateMode(m) { it.copy(ranked = ranked) }
    }

    private var queryJob: Job? = null
    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        queryJob?.cancel()
        queryJob = viewModelScope.launch { delay(180); Mode.entries.forEach(::rerank) }
    }

    fun commitQuery(q: String) {
        if (q.isBlank()) return
        val list = (listOf(q.trim()) + p.recentSearches.filter { !it.equals(q.trim(), true) }).take(8)
        p.recentSearches = list
        _state.update { it.copy(recent = list) }
    }

    fun setFilters(f: Filters) {
        val servicesChanged = f.services != _state.value.filters.services
        p.filters = f
        _state.update { it.copy(filters = f) }
        Mode.entries.forEach(::rerank)
        if (servicesChanged) refresh(_state.value.mode, force = true)
    }

    fun setPersons(n: Int) {
        p.persons = n
        _state.update { it.copy(persons = n) }
        Mode.entries.forEach(::rerank)
        _detail.value?.let { openDetail(it.venue) }
    }

    fun selectPlace(id: String?) {
        p.activePlaceId = id
        _state.update { it.copy(activePlaceId = id, delivery = ModeState(), pickup = ModeState()) }
        refresh(_state.value.mode, force = true)
    }

    fun savePlace(place: SavedPlace) {
        val list = p.places + place
        p.places = list
        _state.update { it.copy(places = list) }
    }

    fun saveCurrentAs(name: String) = viewModelScope.launch {
        val loc = repo.locator.current() ?: run { toast("Kein GPS-Standort verfügbar"); return@launch }
        val (_, label) = repo.locator.reverse(loc.latitude, loc.longitude)
        savePlace(SavedPlace(java.util.UUID.randomUUID().toString(), name, loc.latitude, loc.longitude, label ?: ""))
        toast("„$name“ gespeichert")
    }

    fun deletePlace(id: String) {
        val list = p.places.filter { it.id != id }
        p.places = list
        if (p.activePlaceId == id) selectPlace(null)
        _state.update { it.copy(places = list) }
    }

    suspend fun searchPlaces(q: String) = repo.locator.search(q)

    fun openDetail(v0: Venue) {
        val v = repo.merged(v0)
        val s = _state.value
        val cached = repo.cachedMenu(v)
        _detail.value = DetailState(v, cached, loading = cached == null && v.service.name in setOf("WOLT", "UBER_EATS", "LIEFERANDO"),
            estimate = Savings.estimate(v, s.persons, p.membership, repo.activeVouchers(), cached))
        if (cached == null && _detail.value!!.loading) viewModelScope.launch {
            val dv = repo.details(v)
            val r = runCatching { repo.menu(dv) }
            val items = r.getOrNull()
            _detail.update { d ->
                if (d?.venue?.uid != v.uid) d else d.copy(venue = dv, items = items ?: emptyList(), loading = false,
                    error = r.exceptionOrNull()?.let { "Speisekarte nicht geladen: ${it.message}" },
                    estimate = Savings.estimate(dv, _state.value.persons, p.membership, repo.activeVouchers(), items))
            }
            rerank(v.mode)
        }
    }

    fun closeDetail() { _detail.value = null }

    fun membershipChanged() { Mode.entries.forEach(::rerank) }

    fun scanMail(onDone: (String) -> Unit) = viewModelScope.launch {
        val r = runCatching { MailScanner(p).scan() }
        val msg = r.fold({ "${it.messages} Mails geprüft · ${it.vouchers} Angebote" + if (it.membershipNotes.isNotEmpty()) " · " + it.membershipNotes.distinct().joinToString() else "" },
            { "Mail-Abruf fehlgeschlagen: ${it.message}" })
        onDone(msg)
        Mode.entries.forEach(::rerank)
    }

    fun reschedule() = DealWorker.schedule(getApplication(), p.notifyIntervalMin)

    fun toast(msg: String) = _state.update { it.copy(message = msg) }
    fun messageShown() = _state.update { it.copy(message = null) }
}
