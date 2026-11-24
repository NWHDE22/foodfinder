package de.foodfinder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.foodfinder.data.DealOrigin
import de.foodfinder.data.scopeText
import de.foodfinder.data.Diet
import de.foodfinder.data.DietCheck
import de.foodfinder.data.Mode
import de.foodfinder.data.Savings
import de.foodfinder.data.Service
import de.foodfinder.data.eur
import de.foodfinder.ui.theme.Chalk
import de.foodfinder.ui.theme.LocalAccents

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(d: DetailState, persons: Int, onPersons: (Int) -> Unit, onBack: () -> Unit) {
    val v = d.venue; val e = d.estimate
    val ctx = LocalContext.current
    val acc = LocalAccents.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(v.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (v.mode == Mode.PICKUP) OutlinedButton(onClick = { openRoute(ctx, v) }, modifier = Modifier.height(48.dp)) {
                        Icon(Icons.Filled.Directions, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Route")
                    }
                    Button(onClick = { openUrl(ctx, v.url) }, modifier = Modifier.weight(1f).height(48.dp)) {
                        Text("Bei ${v.service.label} öffnen")
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text((listOf(v.service.label) + v.cuisines.take(4)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                    RatingLine(v, Modifier.padding(vertical = 2.dp))
                    Text(metaLine(v), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    v.address?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.height(20.dp))
                    if (e.savings > 0.009) {
                        Text("−" + eur(e.savings), style = TextStyle(fontFamily = Chalk, fontWeight = FontWeight.Bold, fontSize = 52.sp, lineHeight = 54.sp), color = acc.saving)
                        Text(if (e.exact) "berechnet mit Preisen der Speisekarte" else "geschätzte Ersparnis", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(eur(e.total), style = TextStyle(fontFamily = Chalk, fontWeight = FontWeight.Bold, fontSize = 44.sp))
                        Text("Gerade kein Rabatt gefunden", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (d.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) }

            // ---- the bill
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (v.service == Service.TGTG) "Rechnung" else "Rechnung für $persons", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    PersonsStepper(persons, onPersons)
                }
            }
            items(e.lines) { (label, value) ->
                val isTotal = label.startsWith("Gesamt") || label == "Du zahlst"
                if (isTotal) HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outline)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
                    Text(label, style = if (isTotal) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                        color = if (value < 0) acc.saving else MaterialTheme.colorScheme.onSurface)
                    Text((if (value < 0) "−" else "") + eur(kotlin.math.abs(value)),
                        style = (if (isTotal) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium).copy(fontFeatureSettings = "tnum"),
                        color = if (value < 0) acc.saving else MaterialTheme.colorScheme.onSurface)
                }
                if (isTotal && e.savings > 0.009) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                    Text("statt ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StruckPrice(e.regularTotal, acc.struck)
                }
            }
            if (e.notes.isNotEmpty()) item {
                Text(e.notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }

            // ---- all deals
            val deals = e.appliedDeals + v.deals.filter { x -> e.appliedDeals.none { it.label == x.label } }
            if (deals.isNotEmpty()) {
                item { Section("Angebote") }
                items(deals) { deal ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val icon = when (deal.origin) {
                            DealOrigin.MEMBERSHIP -> Icons.Filled.Verified
                            DealOrigin.EMAIL -> Icons.Filled.Mail
                            DealOrigin.PUSH -> Icons.Filled.Notifications
                            DealOrigin.SERVICE -> Icons.Outlined.LocalOffer
                        }
                        val applied = e.appliedDeals.contains(deal)
                        Icon(icon, null, Modifier.size(18.dp), tint = if (applied) acc.saving else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(deal.label, style = MaterialTheme.typography.bodyMedium)
                            deal.scopeText()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                            val sub = listOfNotNull(originLabel(deal), deal.code?.let { "Code $it" }, deal.minOrder?.let { "ab ${eur(it)}" }, if (applied) "eingerechnet" else null)
                            if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // ---- discounted products
            val items = d.items
            if (items != null) {
                val dietItems = if (d.diet == Diet.ANY) items else items.filter { DietCheck.matches(it, d.diet) }
                if (d.diet != Diet.ANY) {
                    item { Section("${d.diet.label}e Gerichte (${dietItems.size})") }
                    items(dietItems.sortedBy { it.price }.take(30)) { ProductRow(it.name, it.description, it.price, it.originalPrice.takeIf { _ -> it.discounted }) }
                }
                val disc = dietItems.filter { it.discounted }.sortedByDescending { it.saving }
                item { Section(if (disc.isEmpty()) "Reduzierte Produkte" else "Reduzierte Produkte (${disc.size})") }
                if (disc.isEmpty()) item {
                    Text(if (v.deals.any { it.kind.name.startsWith("ITEM") }) "Der Dienst zeigt die reduzierten Produkte nur in der App. Öffne die Speisekarte, um sie zu sehen." else "Keine einzeln reduzierten Produkte gefunden.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                }
                items(disc.take(40)) { it -> ProductRow(it.name, it.description, it.price, it.originalPrice) }
                val mains = items.filter { Savings.isMain(it) && !it.discounted }.sortedBy { it.price }.take(6)
                if (mains.isNotEmpty() && d.diet == Diet.ANY) {
                    item { Section("Günstige Hauptgerichte") }
                    items(mains) { ProductRow(it.name, it.description, it.price, null) }
                }
            }
            d.error?.let { err -> item { Text(err, style = MaterialTheme.typography.bodySmall, color = acc.warn, modifier = Modifier.padding(16.dp)) } }
        }
    }
}

@Composable
private fun Section(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 6.dp))

@Composable
private fun ProductRow(name: String, desc: String, price: Double, orig: Double?) {
    val acc = LocalAccents.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (desc.isNotBlank()) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.size(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(eur(price), style = if (orig != null) TextStyle(fontFamily = Chalk, fontWeight = FontWeight.Bold, fontSize = 22.sp) else MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                color = if (orig != null) acc.saving else MaterialTheme.colorScheme.onSurface)
            if (orig != null) StruckPrice(orig, acc.struck)
        }
    }
}
