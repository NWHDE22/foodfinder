package de.foodfinder.data

import kotlin.math.max
import kotlin.math.min

object DealParser {
    private val pct = Regex("""(\d{1,2})\s?%""")
    private val minRe = Regex("""(?:ab|bestelle für|bestellwert(?: von)?|bestellungen ab|mbw|mindestens)\s*(\d{1,3}(?:[.,]\d{1,2})?)\s?€""")
    private val maxRe = Regex("""bis(?: zu)?\s*(\d{1,3}(?:[.,]\d{1,2})?)\s?€""")
    private val euroRe = Regex("""(\d{1,3}(?:[.,]\d{1,2})?)\s?€|€\s?(\d{1,3}(?:[.,]\d{1,2})?)""")

    private fun num(s: String) = s.replace(',', '.').toDoubleOrNull()

    fun parse(raw: String, origin: DealOrigin = DealOrigin.SERVICE, offerType: String? = null, code: String? = null): Deal? {
        val label = raw.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
        val t = label.lowercase()
        if (t.isBlank() && offerType == null) return null
        val p = pct.find(t)?.groupValues?.get(1)?.let(::num)
        val minOrder = minRe.find(t)?.groupValues?.get(1)?.let(::num)
        val maxDisc = maxRe.find(t)?.groupValues?.get(1)?.let(::num)
        val euros = euroRe.findAll(t).mapNotNull { m -> num(m.groupValues[1].ifEmpty { m.groupValues[2] }) }.toList()
        val firstEuroNotMin = euros.firstOrNull { it != minOrder && it != maxDisc }
        val selected = t.contains("ausgewählt") || t.contains("auf ") && !t.contains("bestellung") && p != null && !t.contains(" ab ")
        val type = offerType?.lowercase()
        val d: Deal? = when {
            type == "stampcard" || t.contains("stempel") -> Deal(DealKind.STAMP_CARD, label.ifBlank { "Stempelkarte" })
            type == "bogof" || t.contains("2 für 1") || t.contains("2für1") || t.contains("2-für-1") || t.contains("1 + 1") || t.contains("1+1") ||
                (t.contains("kauf") && t.contains("gratis")) || t.contains("1 kaufen, 1") || t.contains("buy 1") ->
                Deal(DealKind.BOGO, label.ifBlank { "2 für 1" }, minOrder = minOrder)
            t.contains("halben preis") || t.contains("2. zum") -> Deal(DealKind.HALF_SECOND, label, minOrder = minOrder)
            type == "freeitem" || (t.contains("gratis") && !t.contains("liefer")) ->
                Deal(DealKind.FREE_ITEM, label.ifBlank { "Gratis Produkt" }, minOrder = minOrder)
            t.contains("lieferung") && (t.contains("kostenlos") || t.contains("gratis") || t.contains("0 €") || t.contains("0,00")) ||
                t.contains("liefergebühr") && (t.contains("0 €") || t.contains("0,00") || t.contains("keine")) ->
                Deal(DealKind.FREE_DELIVERY, label, minOrder = minOrder)
            p != null && (type == "itemleveldiscount" || selected) -> Deal(DealKind.ITEM_PERCENT, label, percent = p, minOrder = minOrder)
            p != null -> Deal(DealKind.ORDER_PERCENT, label, percent = p, minOrder = minOrder, maxDiscount = maxDisc)
            type == "itemleveldiscount" && firstEuroNotMin != null -> Deal(DealKind.ITEM_AMOUNT, label, amount = firstEuroNotMin)
            t.contains("bis zu") && maxDisc != null -> Deal(DealKind.ORDER_AMOUNT, label, amount = maxDisc * 0.6, minOrder = minOrder)
            firstEuroNotMin != null && (t.contains("spare") || t.contains("rabatt") || t.contains("gutschein") || t.contains("-") || t.contains("off") || t.contains("guthaben")) ->
                Deal(DealKind.ORDER_AMOUNT, label, amount = firstEuroNotMin, minOrder = minOrder)
            t.contains("deal") || t.contains("angebot") || t.contains("rabatt") -> Deal(DealKind.OTHER, label)
            else -> null
        }
        return d?.copy(origin = origin, code = code)
    }
}

object Savings {
    private val drinkWords = listOf("cola", "fanta", "sprite", "wasser", "bier", "drink", "getränk", "saft", "0,33", "0,5", "1,0 l", "1 l", "dip", "soße", "sauce", "extra", "wein", "riesling", "sekt", "prosecco", "chardonnay", "sauvignon", "0,75", " l ", "flasche")

    fun isMain(i: MenuItem) = i.price in 6.0..35.0 && drinkWords.none { i.name.lowercase().contains(it) }

    fun mainPrice(v: Venue, items: List<MenuItem>?): Double {
        val pool = (items ?: emptyList()).ifEmpty { v.previewItems }.filter(::isMain).map { it.originalPrice ?: it.price }.sorted()
        if (pool.size >= 2) return pool[pool.size / 2]
        return when (v.priceLevel) { 1 -> 10.5; 2 -> 13.5; 3 -> 18.0; 4 -> 24.0; else -> 13.0 }
    }

    fun estimate(
        v: Venue,
        persons: Int,
        membership: MembershipState,
        vouchers: List<Voucher>,
        items: List<MenuItem>? = null,
    ): Estimate {
        val notes = mutableListOf<String>()
        val lines = mutableListOf<Pair<String, Double>>()

        // Too Good To Go: price per bag vs. value
        if (v.service == Service.TGTG) {
            val bags = max(1, min(persons, v.itemsAvailable ?: 1))
            val price = (v.bagPrice ?: 0.0) * bags
            val value = (v.bagValue ?: v.bagPrice ?: 0.0) * bags
            lines += "$bags × Überraschungstüte (Warenwert)" to value
            lines += "Du zahlst" to price
            if (bags < persons) notes += "Nur $bags Tüte(n) verfügbar"
            return Estimate(persons, v.bagPrice ?: 0.0, value, 0.0, value - price, price, value,
                listOf(Deal(DealKind.SURPRISE_BAG, "Überraschungstüte")), lines, true, notes)
        }

        var mp = mainPrice(v, items)
        if (v.service == Service.NEOTASTE) {
            // NeoTaste states the deal's value; a 2-for-1 on a 20 € dish means the dish costs ~20 €
            v.deals.filter { it.label.lowercase().replace(" ", "").contains("2für1") }.maxOfOrNull { it.amount ?: 0.0 }?.let { mp = maxOf(mp, it) }
        }
        var subtotal = persons * mp
        var fill = 0.0
        lines += "$persons × Hauptgericht (ca. ${eur(mp)})" to subtotal
        val delivery = v.mode == Mode.DELIVERY
        if (delivery && v.minOrder != null && v.minOrder > subtotal) {
            notes += "Auf Mindestbestellwert ${eur(v.minOrder)} aufgefüllt"
            fill = v.minOrder - subtotal
            lines += "Auffüllen auf Mindestbestellwert" to fill
            subtotal = v.minOrder
        }
        val fee = if (delivery) v.deliveryFee ?: 0.0 else 0.0
        if (delivery) lines += "Liefergebühr" to fee
        val regular = subtotal + fee

        val discountedMains = (items ?: emptyList()).filter { it.discounted && isMain(it.copy(price = it.originalPrice ?: it.price)) && (it.originalPrice ?: it.price) <= mp * 2.5 }.sortedByDescending { it.saving }
        val dealValues = v.deals.filter { it.origin == DealOrigin.SERVICE || it.origin == DealOrigin.PUSH }.map { d ->
            d to dealValue(d, subtotal, fee, mp, persons, discountedMains)
        }.toMutableList()
        if (discountedMains.isNotEmpty() && v.deals.none { it.kind == DealKind.ITEM_PERCENT || it.kind == DealKind.ITEM_AMOUNT }) {
            val exact = discountedMains.take(persons).sumOf { it.saving }
            dealValues += Deal(DealKind.ITEM_AMOUNT, "Reduzierte Produkte auf der Karte") to exact
        }
        val best = dealValues.maxByOrNull { it.second }?.takeIf { it.second > 0.01 }
        val applied = mutableListOf<Deal>()
        var saving = 0.0
        if (best != null) {
            saving += best.second; applied += best.first
            lines += "${best.first.label}" to -best.second
        }
        if (v.deals.any { it.kind == DealKind.ITEM_PERCENT } && discountedMains.isEmpty() && best?.first?.kind == DealKind.ITEM_PERCENT) {
            notes += "Rabatt nur auf ausgewählte Produkte, Wert geschätzt"
        }

        // Memberships: free delivery
        val freeDeliveryAlready = best?.first?.kind == DealKind.FREE_DELIVERY
        if (delivery && fee > 0 && !freeDeliveryAlready) {
            if (v.service == Service.LIEFERANDO && membership.lieferandoPlus && v.plusEligible != false && subtotal >= membership.lieferandoPlusMin) {
                saving += fee; applied += Deal(DealKind.FREE_DELIVERY, "Lieferando Plus (Prime): 0 € Lieferung", origin = DealOrigin.MEMBERSHIP)
                lines += "Lieferando Plus: Liefergebühr entfällt" to -fee
                if (v.plusEligible == null) notes += "Plus-Teilnahme des Restaurants nicht bestätigt"
            } else if (v.service == Service.WOLT && membership.woltPlusActive() && v.plusEligible == true && subtotal >= membership.woltPlusMin) {
                saving += fee; applied += Deal(DealKind.FREE_DELIVERY, "Wolt+: 0 € Lieferung", origin = DealOrigin.MEMBERSHIP)
                lines += "Wolt+: Liefergebühr entfällt" to -fee
            }
        }

        // Vouchers from e-mail / push for the whole service
        val voucher = vouchers.filter { it.service == v.service && it.deal != null }
            .map { it to dealValue(it.deal!!, subtotal, fee, mp, persons, discountedMains) }
            .filter { it.second > 0.01 }
            .maxByOrNull { it.second }
        if (voucher != null) {
            saving += voucher.second
            applied += voucher.first.deal!!.copy(origin = voucher.first.origin, code = voucher.first.code)
            val src = if (voucher.first.origin == DealOrigin.EMAIL) "E-Mail" else "App-Push"
            lines += "Gutschein aus $src${voucher.first.code?.let { " ($it)" } ?: ""}" to -voucher.second
            if (best != null) notes += "Gutschein evtl. nicht mit Restaurant-Deal kombinierbar"
        }
        saving = min(saving, regular)
        val total = regular - saving
        lines += "Gesamt ca." to total
        if (delivery) notes += "zzgl. evtl. Servicegebühr"
        return Estimate(persons, mp, subtotal, fee, saving, total, regular, applied, lines, items != null, notes, score = saving - fill * 0.5)
    }

    private fun dealValue(d: Deal, subtotal: Double, fee: Double, mp: Double, persons: Int, discounted: List<MenuItem>): Double {
        val minOk = subtotal >= (d.minOrder ?: 0.0)
        return when (d.kind) {
            DealKind.ORDER_PERCENT -> if (minOk) min(subtotal * (d.percent ?: 0.0) / 100, d.maxDiscount ?: Double.MAX_VALUE) else 0.0
            DealKind.ORDER_AMOUNT -> if (minOk) min(d.amount ?: 0.0, subtotal) else 0.0
            DealKind.ITEM_PERCENT ->
                if (discounted.isNotEmpty()) discounted.take(persons).sumOf { it.saving }
                else persons * mp * (d.percent ?: 0.0) / 100 * 0.5
            DealKind.ITEM_AMOUNT ->
                if (discounted.isNotEmpty()) discounted.take(persons).sumOf { it.saving }
                else (d.amount ?: 0.0) * persons * 0.5
            DealKind.BOGO -> if (persons >= 2 && minOk) mp * (persons / 2) else 0.0
            DealKind.HALF_SECOND -> if (persons >= 2) mp * 0.5 * (persons / 2) else 0.0
            DealKind.FREE_ITEM -> if (minOk) 3.5 else 0.0
            DealKind.FREE_DELIVERY -> if (minOk) fee else 0.0
            DealKind.FIXED_VALUE -> if (persons >= 2 || d.label.lowercase().let { !it.contains("2für1") && !it.contains("2 für 1") }) d.amount ?: 0.0 else (d.amount ?: 0.0) / 2
            else -> 0.0
        }
    }
}

fun eur(v: Double): String = String.format(java.util.Locale.GERMANY, "%,.2f €", v)
