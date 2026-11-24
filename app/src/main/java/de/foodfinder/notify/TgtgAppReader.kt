package de.foodfinder.notify

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import de.foodfinder.data.Prefs
import de.foodfinder.data.TgtgBag

/**
 * Reads the bags the official Too Good To Go app shows on screen while the user browses it.
 * Read-only: it never clicks, types or scrolls, and only listens to the TGTG app (see res/xml/tgtg_reader.xml).
 */
class TgtgAppReader : AccessibilityService() {
    private var lastParse = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString()?.startsWith("com.app.tgtg") != true) return
        val now = System.currentTimeMillis()
        if (now - lastParse < 800) return
        lastParse = now
        val root = rootInActiveWindow ?: return
        val bags = runCatching { TgtgScreenParser.parse(root, now) }.getOrDefault(emptyList())
        if (bags.isEmpty()) return
        val prefs = Prefs(applicationContext)
        val keep = prefs.tgtgBags.filter { old -> now - old.seenAt < 12 * 3600_000L && bags.none { it.key == old.key } }
        prefs.tgtgBags = keep + bags
    }

    override fun onInterrupt() {}

    companion object {
        fun enabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return flat.contains(ComponentName(ctx, TgtgAppReader::class.java).flattenToString())
        }
    }
}

/** Turns the TGTG list/detail screens into bags: each clickable card's texts → name, price, value, pickup window, stock. */
object TgtgScreenParser {
    private val euro = Regex("""(\d{1,3}(?:[.,]\d{2}))\s?€|€\s?(\d{1,3}(?:[.,]\d{2}))""")
    private val time = Regex("""\b(\d{1,2}:\d{2})\s*[-–]\s*(\d{1,2}:\d{2})\b""")
    private val left = Regex("""(\d+)\s*(?:übrig|left|verfügbar)""", RegexOption.IGNORE_CASE)
    private val km = Regex("""(\d+(?:[.,]\d+)?)\s*(km|m)\b""")
    private val noise = Regex("""(?i)^(neu|new|\d+[.,]\d|abholen.*|heute.*|morgen.*|ausverkauft|sold out|überraschungstüte|surprise bag|.*€.*|.*\d{1,2}:\d{2}.*|\d+\s*übrig.*|\d+(?:[.,]\d+)?\s*k?m)$""")

    fun parse(root: AccessibilityNodeInfo, now: Long): List<TgtgBag> {
        val cards = mutableListOf<List<String>>()
        collect(root, cards, 0)
        return cards.mapNotNull { texts -> toBag(texts, now) }.distinctBy { it.key }
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<List<String>>, depth: Int) {
        if (depth > 40) return
        if (n.isClickable) {
            val texts = mutableListOf<String>()
            texts(n, texts, 0)
            if (texts.joinToString(" ").let { euro.containsMatchIn(it) }) { out += texts; return }
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, out, depth + 1) }
    }

    private fun texts(n: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 25) return
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && it !in out }?.let(out::add)
        for (i in 0 until n.childCount) n.getChild(i)?.let { texts(it, out, depth + 1) }
    }

    internal fun toBag(texts: List<String>, now: Long): TgtgBag? {
        val all = texts.joinToString(" · ")
        val prices = euro.findAll(all).mapNotNull { m -> m.groupValues[1].ifEmpty { m.groupValues[2] }.replace(',', '.').toDoubleOrNull() }.distinct().toList()
        if (prices.isEmpty()) return null
        val name = texts.flatMap { it.split(" · ", "\n") }.map { it.trim() }
            .firstOrNull { it.length in 2..60 && !noise.matches(it) } ?: return null
        val soldOut = Regex("(?i)ausverkauft|sold out").containsMatchIn(all)
        val window = time.find(all)?.let { "${it.groupValues[1]}–${it.groupValues[2]}" }
        val dist = km.find(all)?.let { m -> m.groupValues[1].replace(',', '.').toDoubleOrNull()?.let { if (m.groupValues[2] == "km") (it * 1000).toInt() else it.toInt() } }
        val price = prices.min()
        val value = prices.max().takeIf { prices.size > 1 }
        return TgtgBag(
            key = name.lowercase() + "|" + (window ?: ""), name = name, price = price, value = value,
            pickupWindow = window, itemsLeft = if (soldOut) 0 else left.find(all)?.groupValues?.get(1)?.toIntOrNull(),
            distanceM = dist, seenAt = now,
        )
    }

    @Suppress("unused") private val r = Rect()
}
