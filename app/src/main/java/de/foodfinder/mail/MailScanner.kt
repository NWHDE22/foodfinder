package de.foodfinder.mail

import de.foodfinder.data.DealOrigin
import de.foodfinder.data.Prefs
import de.foodfinder.data.Service
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Properties
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.search.AndTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.SearchTerm

data class MailPreset(val label: String, val host: String, val port: Int = 993, val hint: String)

val mailPresets = listOf(
    MailPreset("Gmail", "imap.gmail.com", hint = "Google-Konto → Sicherheit → App-Passwörter (2FA nötig)"),
    MailPreset("Outlook / Hotmail", "outlook.office365.com", hint = "Microsoft-Konto → Sicherheit → App-Kennwort"),
    MailPreset("GMX", "imap.gmx.net", hint = "In GMX unter Einstellungen → POP3/IMAP aktivieren"),
    MailPreset("WEB.DE", "imap.web.de", hint = "In WEB.DE unter Einstellungen → POP3/IMAP aktivieren"),
    MailPreset("iCloud", "imap.mail.me.com", hint = "appleid.apple.com → App-spezifisches Passwort"),
    MailPreset("IONOS", "imap.ionos.de", hint = "Normales Postfach-Passwort"),
    MailPreset("Strato", "imap.strato.de", hint = "Normales Postfach-Passwort"),
    MailPreset("Eigener Server", "", hint = "IMAP-Server deines Anbieters (SSL, Port 993)"),
)

class MailScanner(private val prefs: Prefs) {
    private val senders = mapOf(
        "lieferando" to Service.LIEFERANDO, "just-eat" to Service.LIEFERANDO, "takeaway" to Service.LIEFERANDO,
        "uber" to Service.UBER_EATS, "wolt" to Service.WOLT, "toogoodtogo" to Service.TGTG, "neotaste" to Service.NEOTASTE,
        "amazon" to Service.LIEFERANDO,
    )

    data class ScanResult(val messages: Int, val vouchers: Int, val membershipNotes: List<String>)

    suspend fun scan(days: Int = 21): ScanResult = withContext(Dispatchers.IO) {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", prefs.imapHost)
            put("mail.imaps.port", prefs.imapPort.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "30000")
        }
        val store = Session.getInstance(props).getStore("imaps")
        store.connect(prefs.imapHost, prefs.imapPort, prefs.imapUser, prefs.imapPassword)
        var count = 0; var found = 0
        val notes = mutableListOf<String>()
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            val since = Date(System.currentTimeMillis() - days * 24L * 3600_000)
            val from: SearchTerm = OrTerm(senders.keys.map { FromStringTerm(it) }.toTypedArray())
            val msgs: Array<Message> = inbox.search(AndTerm(ReceivedDateTerm(ComparisonTerm.GE, since), from))
            for (msg in msgs.takeLast(150)) {
                count++
                val fromStr = msg.from?.joinToString { it.toString() }?.lowercase() ?: ""
                val subject = msg.subject ?: ""
                if (fromStr.contains("amazon") && !subject.contains("lieferando", true) && !subject.contains("just eat", true)) continue
                val service = senders.entries.firstOrNull { fromStr.contains(it.key) }?.value ?: continue
                if (service == Service.UBER_EATS && !fromStr.contains("eats") && !subject.contains("eats", true)) continue
                val body = runCatching { text(msg) }.getOrDefault("").take(20_000)
                val at = (msg.receivedDate ?: msg.sentDate ?: Date()).time
                Signals.applyMembership(prefs, service, subject, body, at, "E-Mail")?.let { notes += it }
                Signals.voucherFrom(service, subject, body, at, DealOrigin.EMAIL)?.let { Signals.addVoucher(prefs, it); found++ }
            }
            inbox.close(false)
        } finally {
            runCatching { store.close() }
        }
        prefs.imapLastScan = System.currentTimeMillis()
        prefs.imapLastResult = "$count Mails geprüft, $found Angebote gefunden"
        ScanResult(count, found, notes)
    }

    private fun text(p: Part): String = when {
        p.isMimeType("text/plain") -> p.content as String
        p.isMimeType("text/html") -> html(p.content as String)
        p.isMimeType("multipart/*") -> {
            val mp = p.content as Multipart
            val parts = (0 until mp.count).map { mp.getBodyPart(it) }
            val plain = parts.firstOrNull { it.isMimeType("text/plain") }
            if (plain != null && p.isMimeType("multipart/alternative")) text(plain) else parts.joinToString("\n") { runCatching { text(it) }.getOrDefault("") }
        }
        else -> ""
    }

    private fun html(s: String) = s
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</td>|</tr>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&euro;", "€").replace("&#8364;", "€")
        .replace(Regex("&[a-z]+;"), " ")
        .replace(Regex("[ \\t]+"), " ")
}
