package de.foodfinder.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

class Locator(private val ctx: Context) {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun hasPermission() = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun current(): Location? {
        if (!hasPermission()) return null
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 5 * 60_000) return last
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (p in providers) {
                val loc = withTimeoutOrNull(8000) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        val cs = CancellationSignal()
                        cont.invokeOnCancellation { cs.cancel() }
                        runCatching { lm.getCurrentLocation(p, cs, ctx.mainExecutor) { cont.resume(it) } }
                            .onFailure { cont.resume(null) }
                    }
                }
                if (loc != null) return loc
            }
        }
        return last
    }

    suspend fun reverse(lat: Double, lon: Double): Pair<String?, String?> = withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("DEPRECATION")
            val a = Geocoder(ctx, Locale.GERMANY).getFromLocation(lat, lon, 1)?.firstOrNull()
            val label = listOfNotNull(a?.thoroughfare?.let { t -> t + (a.subThoroughfare?.let { " $it" } ?: "") }, a?.locality).joinToString(", ")
            a?.locality to label.ifBlank { null }
        }.getOrDefault(null to null)
    }

    suspend fun search(query: String): List<SavedPlace> = withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(ctx, Locale.GERMANY).getFromLocationName(query, 5).orEmpty().map { a ->
                val line = (0..a.maxAddressLineIndex).joinToString(", ") { a.getAddressLine(it) }
                SavedPlace(java.util.UUID.randomUUID().toString(), query, a.latitude, a.longitude, line)
            }
        }.getOrDefault(emptyList())
    }
}
