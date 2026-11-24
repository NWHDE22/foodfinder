package de.foodfinder.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import de.foodfinder.data.Deal
import de.foodfinder.data.DealOrigin
import de.foodfinder.data.Mode
import de.foodfinder.data.Venue
import de.foodfinder.data.eur

/** The signature move: a chalk stroke across an old price, drawn slightly uphill like a hand would. */
fun Modifier.chalkStrike(color: Color) = this.drawWithContent {
    drawContent()
    val w = 2.dp.toPx()
    drawLine(color, Offset(-2f, size.height * 0.64f), Offset(size.width + 2f, size.height * 0.40f), strokeWidth = w, cap = StrokeCap.Round)
}

@Composable
fun StruckPrice(value: Double, color: Color, style: TextStyle = MaterialTheme.typography.bodySmall) {
    Text(eur(value), style = style.copy(fontFeatureSettings = "tnum"), color = color, modifier = Modifier.chalkStrike(color.copy(alpha = 0.9f)))
}

@Composable
fun PersonsStepper(n: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        IconButton(onClick = { onChange((n - 1).coerceAtLeast(1)) }, enabled = n > 1) {
            Icon(Icons.Filled.Remove, contentDescription = "Eine Person weniger", modifier = Modifier.size(20.dp))
        }
        Text("$n Pers.", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 2.dp))
        IconButton(onClick = { onChange((n + 1).coerceAtMost(8)) }, enabled = n < 8) {
            Icon(Icons.Filled.Add, contentDescription = "Eine Person mehr", modifier = Modifier.size(20.dp))
        }
    }
}

fun metaLine(v: Venue): String = buildList {
    if (v.mode == Mode.DELIVERY) {
        if (v.etaMin != null) add(if (v.etaMax != null && v.etaMax != v.etaMin) "${v.etaMin}–${v.etaMax} Min" else "${v.etaMin} Min")
        v.minOrder?.takeIf { it > 0 }?.let { add("MBW ${eur(it)}") }
        v.deliveryFee?.let { add(if (it == 0.0) "0 € Lieferung" else "Lieferung ${eur(it)}") }
    } else {
        v.distanceM?.let { add(if (it < 1000) "$it m" else String.format(java.util.Locale.GERMANY, "%.1f km", it / 1000.0)) }
        v.pickupWindow?.let { add("Abholung $it") }
        v.itemsAvailable?.let { add("$it übrig") }
    }
}.joinToString(" · ")

fun originLabel(d: Deal) = when (d.origin) {
    DealOrigin.MEMBERSHIP -> "Mitgliedschaft"
    DealOrigin.EMAIL -> "aus E-Mail"
    DealOrigin.PUSH -> "aus App-Push"
    DealOrigin.SERVICE -> null
}

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun openApp(ctx: Context, packages: List<String>, fallbackUrl: String) {
    val pm = ctx.packageManager
    val intent = packages.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
    if (intent != null) ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else openUrl(ctx, fallbackUrl)
}

fun openRoute(ctx: Context, v: Venue) {
    val q = if (v.lat != null && v.lon != null) "geo:${v.lat},${v.lon}?q=${v.lat},${v.lon}(${Uri.encode(v.name)})" else "geo:0,0?q=${Uri.encode(v.name + " " + (v.address ?: ""))}"
    openUrl(ctx, q)
}

/** Rating shown on every restaurant: star icon, value out of 5, number of ratings. */
@Composable
fun RatingLine(v: Venue, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (v.rating != null && v.rating > 0) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Star, contentDescription = "Bewertung",
                modifier = Modifier.size(14.dp), tint = de.foodfinder.ui.theme.LocalAccents.current.saving)
            Text(" " + String.format(java.util.Locale.GERMANY, "%.1f", v.rating), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurface)
            v.ratingCount?.takeIf { it.isNotBlank() && it != "0" }?.let {
                Text(" ($it)", style = MaterialTheme.typography.bodySmall, color = color)
            }
        } else {
            Text("Noch keine Bewertung", style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}
