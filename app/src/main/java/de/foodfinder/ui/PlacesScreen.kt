package de.foodfinder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import de.foodfinder.data.SavedPlace
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(vm: MainViewModel, state: UiState) {
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Orte") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Ort hinzufügen") })
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                ListItem(
                    headlineContent = { Text("Aktueller Standort") },
                    supportingContent = { Text("GPS, bei jedem Laden neu ermittelt") },
                    leadingContent = { Icon(Icons.Filled.MyLocation, null) },
                    trailingContent = { RadioButton(state.activePlaceId == null, { vm.selectPlace(null) }) },
                    modifier = Modifier.clickable { vm.selectPlace(null) },
                )
            }
            items(state.places, key = { it.id }) { p ->
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = { if (p.address.isNotBlank()) Text(p.address, maxLines = 2) },
                    leadingContent = { Icon(Icons.Filled.Place, null) },
                    trailingContent = {
                        androidx.compose.foundation.layout.Row {
                            IconButton(onClick = { vm.deletePlace(p.id) }) { Icon(Icons.Outlined.Delete, "„${p.name}“ löschen") }
                            RadioButton(state.activePlaceId == p.id, { vm.selectPlace(p.id) })
                        }
                    },
                    modifier = Modifier.clickable { vm.selectPlace(p.id) },
                )
            }
            if (state.places.isEmpty()) item {
                Text("Speichere Orte wie Zuhause oder Büro, um dort schnell nach Angeboten zu suchen. Die Hintergrund-Prüfung nutzt den gewählten Ort bzw. den zuletzt bekannten Standort.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
    if (adding) AddPlaceDialog(vm) { adding = false }
}

@Composable
private fun AddPlaceDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SavedPlace>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun search() = scope.launch { searching = true; results = vm.searchPlaces(query); searching = false }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ort hinzufügen") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name, z. B. Zuhause") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(query, { query = it }, label = { Text("Adresse suchen") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
                TextButton(onClick = { search() }, enabled = query.length > 2) { Text(if (searching) "Suche…" else "Adresse suchen") }
                results.forEach { r ->
                    ListItem(headlineContent = { Text(r.address, maxLines = 2) }, modifier = Modifier.clickable {
                        vm.savePlace(r.copy(name = name.ifBlank { query })); onDismiss()
                    })
                }
                if (!searching && results.isEmpty() && query.length > 2) Text("Tippe auf „Adresse suchen“ und wähle einen Treffer.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { vm.saveCurrentAs(name.ifBlank { "Mein Ort" }); onDismiss() }) { Text("Aktuellen Standort speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
