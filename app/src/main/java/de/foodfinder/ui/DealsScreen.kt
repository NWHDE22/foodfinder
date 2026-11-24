package de.foodfinder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.foodfinder.data.Filters
import de.foodfinder.data.Mode
import de.foodfinder.data.Ranked
import de.foodfinder.data.Service
import de.foodfinder.data.SortBy
import de.foodfinder.data.Synonyms
import de.foodfinder.data.eur
import de.foodfinder.data.servicesFor
import de.foodfinder.ui.theme.LocalAccents
import de.foodfinder.ui.theme.moneyStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DealsScreen(vm: MainViewModel, state: UiState, mode: Mode, onPlaces: () -> Unit) {
    val ms = state.of(mode)
    var showFilters by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(if (mode == Mode.DELIVERY) "Liefern" else "Abholen & vor Ort", style = MaterialTheme.typography.titleLarge)
                    Row(Modifier.clickable(onClick = onPlaces).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (state.activePlaceId == null) Icons.Filled.MyLocation else Icons.Filled.Place, null,
                            Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.size(4.dp))
                        Text(state.placeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            actions = {
                PersonsStepper(state.persons, vm::setPersons)
                IconButton(onClick = { vm.refresh(mode, force = true) }) { Icon(Icons.Filled.Refresh, "Neu laden") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )

        // --- search with suggestions
        OutlinedTextField(
            value = state.query, onValueChange = vm::setQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Worauf hast du Hunger?") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Filled.Close, "Suche leeren") } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.commitQuery(state.query); focus.clearFocus() }),
            shape = MaterialTheme.shapes.extraLarge,
        )
        val topCuisines = remember(ms.venues) {
            ms.venues.flatMap { it.cuisines }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(10).map { it.key }
        }
        val suggestions = remember(state.query, state.recent, topCuisines) {
            if (state.query.isNotBlank()) emptyList()
            else (state.recent.take(3).map { it to true } + Synonyms.forNow().map { it to false } + topCuisines.map { it to false })
                .distinctBy { it.first.lowercase() }.take(14)
        }
        if (suggestions.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                items(suggestions, key = { it.first }) { (s, recent) ->
                    AssistChip(onClick = { vm.setQuery(s); vm.commitQuery(s) }, label = { Text(s) },
                        leadingIcon = if (recent) ({ Icon(Icons.Filled.History, null, Modifier.size(AssistChipDefaults.IconSize)) }) else null)
                }
            }
        }
        // --- quick filters
        QuickFilters(state.filters, mode, onChange = vm::setFilters, onMore = { showFilters = true })

        if (ms.loading || ms.enriching) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.height(2.dp))

        PullToRefreshBox(isRefreshing = false, onRefresh = { vm.refresh(mode, force = true) }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "status") { SourceStrip(ms, mode) }
                if (state.needsLocation && ms.at == null && !ms.loading) item(key = "noloc") {
                    EmptyBlock("Kein Standort", "Erlaube den Standortzugriff oder wähle unter „Orte“ einen gespeicherten Ort.", "Ort wählen", onPlaces)
                } else if (!ms.loading && ms.ranked.isEmpty() && ms.loadedAt > 0) item(key = "empty") {
                    EmptyBlock("Nichts gefunden", if (state.query.isNotBlank()) "Keine Treffer für „${state.query}“ mit diesen Filtern." else "Mit den aktuellen Filtern bleibt nichts übrig.",
                        "Filter zurücksetzen") { vm.setFilters(Filters()); vm.setQuery("") }
                }
                items(ms.ranked, key = { it.venue.uid }) { r ->
                    DealRow(r, state.persons) { vm.openDetail(r.venue) }
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    if (showFilters) {
        FilterSheet(state.filters, mode, state.of(mode).venues.flatMap { it.cuisines }, onChange = vm::setFilters, onDismiss = { showFilters = false })
    }
}

@Composable
private fun QuickFilters(f: Filters, mode: Mode, onChange: (Filters) -> Unit, onMore: () -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { AssistChip(onClick = onMore, label = { Text("Filter") }, leadingIcon = { Icon(Icons.Filled.Tune, null, Modifier.size(18.dp)) }) }
        item { FilterChip(f.onlyWithDeals, { onChange(f.copy(onlyWithDeals = !f.onlyWithDeals)) }, label = { Text("Nur mit Ersparnis") }) }
        if (mode == Mode.DELIVERY) {
            item { FilterChip(f.maxEta == 30, { onChange(f.copy(maxEta = if (f.maxEta == 30) null else 30)) }, label = { Text("bis 30 Min") }) }
            item { FilterChip(f.maxMinOrder == 15.0, { onChange(f.copy(maxMinOrder = if (f.maxMinOrder == 15.0) null else 15.0)) }, label = { Text("MBW bis 15 €") }) }
            item { FilterChip(f.maxDeliveryFee == 0.0, { onChange(f.copy(maxDeliveryFee = if (f.maxDeliveryFee == 0.0) null else 0.0)) }, label = { Text("0 € Lieferung") }) }
        }
        mode.servicesFor.forEach { s ->
            item(key = s.name) {
                FilterChip(s in f.services, {
                    val next = if (s in f.services) f.services - s else f.services + s
                    if (next.isNotEmpty()) onChange(f.copy(services = next))
                }, label = { Text(s.label) })
            }
        }
    }
}

@Composable
private fun SourceStrip(ms: ModeState, mode: Mode) {
    if (ms.status.isEmpty()) return
    val bad = ms.status.filter { !it.ok }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(ms.status.filter { it.ok }.joinToString(" · ") { "${it.service.label} ${it.count}" }.ifEmpty { "Keine Quelle erreichbar" },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        bad.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Icon(Icons.Filled.ErrorOutline, null, Modifier.size(14.dp), tint = LocalAccents.current.warn)
                Spacer(Modifier.size(4.dp))
                Text("${s.service.label}: ${s.message}", style = MaterialTheme.typography.labelSmall, color = LocalAccents.current.warn)
            }
        }
    }
}

@Composable
fun DealRow(r: Ranked, persons: Int, onClick: () -> Unit) {
    val v = r.venue; val e = r.estimate
    val acc = LocalAccents.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(v.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val cuisine = (listOf(v.service.label) + v.cuisines.take(3)).joinToString(" · ")
            Text(cuisine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(metaLine(v), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val shown = (e.appliedDeals + v.deals.filter { d -> e.appliedDeals.none { it.label == d.label } }).take(3)
            shown.forEachIndexed { i, d ->
                Text(d.label + (originLabel(d)?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall,
                    color = if (i < e.appliedDeals.size) acc.saving else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (i == 0 && e.savings > 0) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = if (i == 0) 4.dp else 0.dp))
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 96.dp)) {
            if (e.savings > 0.009) {
                Text("−" + eur(e.savings), style = moneyStyle(e.savings), color = acc.saving, textAlign = TextAlign.End)
                Text(if (e.exact) "gespart" else "ca. gespart", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Text(if (v.service == Service.TGTG) "${e.persons.coerceAtMost(v.itemsAvailable ?: 1)} Tüte(n)" else "für $persons", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(eur(e.total), style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"))
            if (e.savings > 0.009) StruckPrice(e.regularTotal, acc.struck)
        }
    }
}

@Composable
private fun EmptyBlock(title: String, text: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAction) { Text(action) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(f: Filters, mode: Mode, allCuisines: List<String>, onChange: (Filters) -> Unit, onDismiss: () -> Unit) {
    val cuisines = remember(allCuisines) { allCuisines.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(28).map { it.key } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { SheetHeading("Sortieren nach") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SortBy.entries.filter { mode == Mode.DELIVERY || it != SortBy.ETA }.forEach { s ->
                        FilterChip(f.sort == s, { onChange(f.copy(sort = s)) }, label = { Text(s.label) })
                    }
                }
            }
            item { SheetHeading("Küche") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cuisines.forEach { c ->
                        FilterChip(c in f.cuisines, { onChange(f.copy(cuisines = if (c in f.cuisines) f.cuisines - c else f.cuisines + c)) }, label = { Text(c) })
                    }
                    if (cuisines.isEmpty()) Text("Erst nach dem Laden verfügbar", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (mode == Mode.DELIVERY) {
                item { SheetHeading("Lieferzeit höchstens") }
                item { Options(listOf(null, 20, 30, 45, 60), f.maxEta, { it?.let { "$it Min" } ?: "egal" }) { onChange(f.copy(maxEta = it)) } }
                item { SheetHeading("Mindestbestellwert höchstens") }
                item { Options(listOf(null, 10.0, 15.0, 20.0, 25.0), f.maxMinOrder, { it?.let(::eur) ?: "egal" }) { onChange(f.copy(maxMinOrder = it)) } }
                item { SheetHeading("Liefergebühr höchstens") }
                item { Options(listOf(null, 0.0, 1.0, 2.0, 3.0), f.maxDeliveryFee, { it?.let(::eur) ?: "egal" }) { onChange(f.copy(maxDeliveryFee = it)) } }
            }
            item { SheetHeading("Bewertung mindestens") }
            item { Options(listOf(null, 4.0, 4.3, 4.5), f.minRating, { it?.let { r -> String.format(java.util.Locale.GERMANY, "%.1f", r) } ?: "egal" }) { onChange(f.copy(minRating = it)) } }
            item { SwitchRow("Nur geöffnet", f.onlyOpen) { onChange(f.copy(onlyOpen = it)) } }
            item { SwitchRow("Nur mit Ersparnis", f.onlyWithDeals) { onChange(f.copy(onlyWithDeals = it)) } }
            item { SwitchRow("Supermärkte & Shops ausblenden", f.hideGroceries) { onChange(f.copy(hideGroceries = it)) } }
            item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { TextButton(onClick = { onChange(Filters(services = f.services)) }) { Text("Zurücksetzen") } } }
        }
    }
}

@Composable
private fun SheetHeading(t: String) = Text(t, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Options(values: List<T?>, current: T?, label: (T?) -> String, onPick: (T?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { v -> FilterChip(current == v, { onPick(v) }, label = { Text(label(v)) }) }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}
