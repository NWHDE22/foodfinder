package de.foodfinder.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object Http {
    const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; encodeDefaults = true }

    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val list = cookieStore.getOrPut(url.host) { mutableListOf() }
                synchronized(list) {
                    cookies.forEach { c -> list.removeAll { it.name == c.name }; list.add(c) }
                }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                cookieStore[url.host]?.let { synchronized(it) { it.toList() } } ?: emptyList()
        })
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    class HttpError(val code: Int, msg: String) : IOException(msg)

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val rb = Request.Builder().url(url).header("User-Agent", BROWSER_UA).header("Accept-Language", "de-DE,de;q=0.9")
        headers.forEach { (k, v) -> rb.header(k, v) }
        client.newCall(rb.build()).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) throw HttpError(r.code, "HTTP ${r.code}")
            body
        }
    }

    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val rb = Request.Builder().url(url).post(body.toRequestBody(JSON_TYPE))
                .header("Accept-Language", "de-DE,de;q=0.9")
            if (headers.keys.none { it.equals("User-Agent", true) }) rb.header("User-Agent", BROWSER_UA)
            headers.forEach { (k, v) -> rb.header(k, v) }
            client.newCall(rb.build()).execute().use { r -> r.code to (r.body?.string() ?: "") }
        }

    fun parse(s: String): JsonElement = json.parseToJsonElement(s)
}

// ---- defensive JSON helpers ----
operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
operator fun JsonElement?.get(i: Int): JsonElement? = (this as? JsonArray)?.getOrNull(i)
val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()
val JsonElement?.obj: JsonObject? get() = this as? JsonObject
val JsonElement?.str: String?
    get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
        ?: (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
val JsonElement?.dbl: Double? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.doubleOrNull ?: it.content.replace(',', '.').toDoubleOrNull() }
val JsonElement?.int: Int? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

/** Walk every string value in a JSON tree. */
fun JsonElement?.forEachString(block: (String, String) -> Unit, key: String = "") {
    when (this) {
        is JsonObject -> this.forEach { (k, v) -> v.forEachString(block, k) }
        is JsonArray -> this.forEach { it.forEachString(block, key) }
        is JsonPrimitive -> if (this !is JsonNull && isString) block(key, content)
        else -> {}
    }
}

/** Parses "21,90 €" / "€21.90" style amounts. */
fun parseEuro(s: String?): Double? {
    if (s == null) return null
    val m = Regex("""(\d{1,4}(?:[.,]\d{1,2})?)\s?€|€\s?(\d{1,4}(?:[.,]\d{1,2})?)""").find(s) ?: return null
    val v = m.groupValues[1].ifEmpty { m.groupValues[2] }
    return v.replace(',', '.').toDoubleOrNull()
}

fun slugify(s: String): String = s.lowercase()
    .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
    .replace(Regex("[^a-z0-9]+"), "-").trim('-')

fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2).let { it * it } +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
    return (2 * r * Math.asin(Math.sqrt(a))).toInt()
}
