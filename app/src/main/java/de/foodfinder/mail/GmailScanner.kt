package de.foodfinder.mail

import android.util.Base64
import de.foodfinder.data.Http
import de.foodfinder.data.Prefs
import de.foodfinder.data.arr
import de.foodfinder.data.get
import de.foodfinder.data.str
import kotlinx.serialization.json.JsonElement
import java.net.URLEncoder

/** Reads delivery-service mails through the Gmail REST API (no Play Services). */
class GmailScanner(private val prefs: Prefs, private val auth: GmailAuth) {
    private val base = "https://gmail.googleapis.com/gmail/v1/users/me/messages"

    suspend fun scan(): MailScanner.ScanResult {
        val token = auth.accessToken() ?: throw IllegalStateException("Gmail-Anmeldung abgelaufen. Bitte neu anmelden.")
        val h = mapOf("Authorization" to "Bearer $token")
        val q = "newer_than:21d from:(lieferando OR just-eat OR takeaway OR ubereats OR uber OR wolt OR toogoodtogo OR neotaste OR amazon)"
        val list = Http.parse(Http.get("$base?q=${URLEncoder.encode(q, "UTF-8")}&maxResults=100", h))
        var count = 0; var found = 0
        val notes = mutableListOf<String>()
        for (m in list["messages"].arr) {
            val id = m["id"].str ?: continue
            val msg = runCatching { Http.parse(Http.get("$base/$id?format=full", h)) }.getOrNull() ?: continue
            count++
            val headers = msg["payload"]["headers"].arr
            fun header(n: String) = headers.firstOrNull { it["name"].str.equals(n, true) }["value"].str ?: ""
            val fromStr = header("From").lowercase()
            val subject = header("Subject")
            val at = msg["internalDate"].str?.toLongOrNull() ?: System.currentTimeMillis()
            val body = runCatching { bodyText(msg["payload"]) }.getOrDefault("").take(20_000)
            if (processMail(prefs, fromStr, subject, body, at, notes)) found++
        }
        prefs.imapLastScan = System.currentTimeMillis()
        prefs.imapLastResult = "$count Mails geprüft, $found Angebote gefunden"
        return MailScanner.ScanResult(count, found, notes)
    }

    private fun decode(data: String?): String =
        if (data.isNullOrEmpty()) "" else String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)

    private fun bodyText(part: JsonElement?): String {
        val mime = part["mimeType"].str ?: ""
        val subParts = part["parts"].arr
        return when {
            subParts.isNotEmpty() -> {
                val plain = subParts.firstOrNull { it["mimeType"].str == "text/plain" }
                if (plain != null && mime == "multipart/alternative") bodyText(plain)
                else subParts.joinToString("\n") { runCatching { bodyText(it) }.getOrDefault("") }
            }
            mime == "text/plain" -> decode(part["body"]["data"].str)
            mime == "text/html" -> htmlToText(decode(part["body"]["data"].str))
            else -> ""
        }
    }
}
