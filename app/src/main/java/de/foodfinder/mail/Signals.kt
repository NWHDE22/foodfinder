package de.foodfinder.mail

import de.foodfinder.data.DealKind
import de.foodfinder.data.DealOrigin
import de.foodfinder.data.DealParser
import de.foodfinder.data.MembershipState
import de.foodfinder.data.Prefs
import de.foodfinder.data.Service
import de.foodfinder.data.Voucher
import java.text.SimpleDateFormat
import java.util.Locale

/** Turns mail / push text into vouchers and membership status updates. */
object Signals {
    private val codeRe = Regex("""(?i)(?:code|gutscheincode|promo-?code|rabattcode|aktionscode)\s*[:\-]?\s*["„“»]?([A-Z0-9][A-Z0-9\-]{3,19})""")
    private val untilRe = Regex("""(?i)(?:gültig bis|bis zum|bis|endet am|läuft am|valid until)\s*(\d{1,2}\.\s?\d{1,2}\.(?:\s?\d{2,4})?)""")
    private val dealSentence = Regex("""[^.!?\n]*?(?:\d{1,2}\s?%|\d{1,3}(?:,\d{2})?\s?€|2\s?für\s?1|gratis|kostenlos)[^.!?\n]*""", RegexOption.IGNORE_CASE)
    private val stopWords = listOf("bestellbestätigung", "deine bestellung", "rechnung", "quittung", "beleg", "lieferung ist unterwegs", "wurde zugestellt", "bewerte", "receipt", "passwort")

    fun voucherFrom(service: Service, subject: String, body: String, at: Long, origin: DealOrigin): Voucher? {
        val text = "$subject\n$body"
        val low = text.lowercase()
        if (stopWords.any { low.contains(it) } && !low.contains("gutschein")) return null
        val candidates = dealSentence.findAll(text).map { it.value.trim() }.filter { it.length in 6..160 }.toList()
        val deal = (listOf(subject) + candidates).asSequence()
            .mapNotNull { DealParser.parse(it, origin) }
            .firstOrNull { it.kind in setOf(DealKind.ORDER_PERCENT, DealKind.ORDER_AMOUNT, DealKind.FREE_DELIVERY, DealKind.BOGO, DealKind.FREE_ITEM) }
        val code = codeRe.find(text)?.groupValues?.get(1)?.takeIf { it.any(Char::isDigit) || it.length >= 6 }
        if (deal == null && code == null) return null
        return Voucher(
            service = service, title = subject.take(120), deal = deal, code = code,
            validUntil = untilRe.find(text)?.groupValues?.get(1)?.replace(" ", ""),
            receivedAt = at, origin = origin,
            snippet = (candidates.firstOrNull() ?: body.take(140)).take(180),
        )
    }

    /** Apply membership hints. Returns a human readable note if something changed. */
    fun applyMembership(prefs: Prefs, service: Service?, subject: String, body: String, at: Long, source: String): String? {
        val text = "$subject $body"
        val low = text.lowercase()
        var m: MembershipState = prefs.membership
        var note: String? = null
        val date = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(at)
        val isWoltPlus = service == Service.WOLT && (low.contains("wolt+") || low.contains("wolt plus"))
        if (isWoltPlus) {
            val ends = Regex("""(?i)(endet|läuft|abgelaufen|gekündigt|kündigung|expires|ends)""").containsMatchIn(text)
            val renewed = Regex("""(?i)(verlängert|willkommen bei wolt\+|abo ist aktiv|erfolgreich abonniert|renewed)""").containsMatchIn(text)
            val endDate = untilRe.find(text)?.groupValues?.get(1)?.let(::parseDate)
            if (Regex("(?i)abgelaufen|ist beendet|has ended|expired").containsMatchIn(text)) {
                m = m.copy(woltPlus = false, woltNote = "Abgelaufen laut $source vom $date"); note = "Wolt+ abgelaufen"
            } else if (ends) {
                m = m.copy(woltPlusEnds = endDate ?: m.woltPlusEnds, woltNote = "Läuft aus laut $source vom $date"); note = "Wolt+ läuft aus"
            } else if (renewed) {
                m = m.copy(woltPlus = true, woltPlusEnds = endDate, woltNote = "Aktiv laut $source vom $date"); note = "Wolt+ aktiv"
            }
        }
        val isLiefPlus = service == Service.LIEFERANDO && (low.contains("lieferando plus") || low.contains("lieferando+") || low.contains("just eat+") || low.contains("prime"))
        if (isLiefPlus) {
            if (Regex("(?i)beendet|abgelaufen|gekündigt|nicht mehr aktiv|entfernt").containsMatchIn(text)) {
                m = m.copy(lieferandoPlus = false, lieferandoNote = "Beendet laut $source vom $date"); note = "Lieferando Plus beendet"
            } else if (Regex("(?i)aktiviert|verknüpft|willkommen|ist aktiv|freigeschaltet").containsMatchIn(text)) {
                m = m.copy(lieferandoPlus = true, lieferandoNote = "Aktiv laut $source vom $date"); note = "Lieferando Plus aktiv"
            }
        }
        if (note != null) prefs.membership = m
        return note
    }

    private fun parseDate(s: String): Long? {
        val parts = s.trim().trimEnd('.').split(".").mapNotNull { it.trim().toIntOrNull() }
        if (parts.size < 2) return null
        val now = java.time.LocalDate.now()
        val y = parts.getOrNull(2)?.let { if (it < 100) 2000 + it else it } ?: now.year
        return runCatching {
            java.time.LocalDate.of(y, parts[1], parts[0]).atTime(23, 59).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    fun addVoucher(prefs: Prefs, v: Voucher) {
        val existing = prefs.vouchers
        if (existing.any { it.title == v.title && it.service == v.service && it.code == v.code }) return
        prefs.vouchers = existing + v
    }
}
