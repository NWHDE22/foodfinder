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
        val delivery = v.mode == Mode.DELIVERY
        fun feeFor(sub: Double) = if (!delivery) 0.0 else v.feeBands.filter { sub + 0.001 >= it.minAmount }.maxByOrNull { it.minAmount }?.fee
            ?: v.feeBands.minByOrNull { it.minAmount }?.fee ?: v.deliveryFee ?: 0.0

        val menu = items ?: emptyList()
        // products that a deal names: their real prices make the basket, not an average dish
        val discountedMains = menu.filter { it.discounted && isMain(it.copy(price = it.originalPrice ?: it.price)) }.sortedByDescending { it.saving }
        val bogoItems = menu.filter { m -> m.labels.any { it == "BOGO" } && m.price >= 3 }.sortedBy { it.price }

        /** One way to order: what goes in the basket, what it would cost without deals, what the deal saves. */
        class Scenario(val deal: Deal?, val basketLabel: String, val basket: Double, val saving: Double)

        val scenarios = mutableListOf<Scenario>()
        val avgBasket = persons * mp
        val avgLabel = "$persons × Hauptgericht (ca. ${eur(mp)})"
        scenarios += Scenario(null, avgLabel, avgBasket, 0.0)
        val serviceDeals = v.deals.filter { it.origin == DealOrigin.SERVICE || it.origin == DealOrigin.PUSH }.toMutableList()
        if (discountedMains.isNotEmpty() && serviceDeals.none { it.kind == DealKind.ITEM_PERCENT || it.kind == DealKind.ITEM_AMOUNT })
            serviceDeals += Deal(DealKind.ITEM_AMOUNT, "Reduzierte Produkte auf der Karte")
        for (d in serviceDeals) {
            when {
                (d.kind == DealKind.ITEM_PERCENT || d.kind == DealKind.ITEM_AMOUNT) && discountedMains.isNotEmpty() -> {
                    // order the reduced dishes: the best `persons` of them (cheapest final price first among good savings)
                    val pick = discountedMains.take(persons)
                    val basket = pick.sumOf { it.originalPrice ?: it.price } + (persons - pick.size).coerceAtLeast(0) * mp
                    if (scenarios.none { it.deal?.label?.startsWith("Reduzierte Artikel") == true }) {
                        val sav = pick.sumOf { it.saving }
                        val named = Deal(DealKind.ITEM_AMOUNT, "Reduzierte Artikel: " + pick.joinToString(", ") { it.name }, amount = sav, items = pick.map { it.name })
                        scenarios += Scenario(named, pick.joinToString(" + ") { it.name.take(28) } + if (pick.size < persons) " + ${persons - pick.size} × Hauptgericht" else "",
                            basket, sav)
                    }
                }
                (d.kind == DealKind.BOGO || d.kind == DealKind.HALF_SECOND) && bogoItems.isNotEmpty() && persons >= 2 -> {
                    val unit = bogoItems[bogoItems.size / 2].price
                    val pairs = persons / 2
                    val basket = pairs * 2 * unit + (persons % 2) * mp
                    val sav = pairs * unit * if (d.kind == DealKind.BOGO) 1.0 else 0.5
                    scenarios += Scenario(d, "${pairs * 2} × ${bogoItems[bogoItems.size / 2].name.take(28)}" + if (persons % 2 == 1) " + 1 × Hauptgericht" else "", basket, sav)
                }
                else -> {
                    val sub0 = maxOf(avgBasket, if (delivery) v.minOrder ?: 0.0 else 0.0)
                    val value = dealValue(d, sub0, feeFor(sub0), mp, persons, discountedMains, null)
                    if (value > 0.01) scenarios += Scenario(d, avgLabel, avgBasket, value)
                }
            }
        }
        if (v.deals.any { it.kind == DealKind.ITEM_PERCENT } && discountedMains.isEmpty()) notes += "Rabatt nur auf ausgewählte Produkte, Wert geschätzt"

        fun fillOf(sc: Scenario) = if (delivery && v.minOrder != null && v.minOrder > sc.basket) v.minOrder - sc.basket else 0.0
        // best = most money saved, minus half of what you would only add to reach the minimum order
        val best = scenarios.maxByOrNull { it.saving - fillOf(it) * 0.5 }!!
        val fill = fillOf(best)
        val subtotal = best.basket + fill
        lines += best.basketLabel to best.basket
        if (fill > 0) { notes += "Auf Mindestbestellwert ${eur(v.minOrder!!)} aufgefüllt"; lines += "Auffüllen auf Mindestbestellwert" to fill }
        val fee = feeFor(subtotal)
        if (delivery) lines += "Liefergebühr" to fee
        val regular = subtotal + fee

        val applied = mutableListOf<Deal>()
        var saving = 0.0
        best.deal?.let { d ->
            saving += best.saving; applied += d
            lines += d.label to -best.saving
        }

        // Memberships: free delivery
        val freeDeliveryAlready = best.deal?.kind == DealKind.FREE_DELIVERY
        if (delivery && fee > 0 && !freeDeliveryAlready) {
            if (v.service == Service.LIEFERANDO && membership.lieferandoPlus) {
                when {
                    v.plusEligible != true -> notes += "Lieferando Plus: Teilnahme dieses Restaurants nicht bestätigt – Liefergebühr bleibt eingerechnet"
                    subtotal < membership.lieferandoPlusMin -> notes += "Lieferando Plus erst ab ${eur(membership.lieferandoPlusMin)}"
                    else -> {
                        saving += fee; applied += Deal(DealKind.FREE_DELIVERY, "Lieferando Plus (Prime): 0 € Lieferung", origin = DealOrigin.MEMBERSHIP)
                        lines += "Lieferando Plus: Liefergebühr entfällt" to -fee
                    }
                }
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
            if (best.deal != null) notes += "Gutschein evtl. nicht mit Restaurant-Deal kombinierbar"
        }
        saving = min(saving, regular)
        val total = regular - saving
        lines += "Gesamt ca." to total
        if (delivery) notes += "zzgl. evtl. Servicegebühr"
        return Estimate(persons, mp, subtotal, fee, saving, total, regular, applied, lines, items != null, notes, score = saving - fill * 0.5)
    }

    private fun dealValue(d: Deal, subtotal: Double, fee: Double, mp: Double, persons: Int, discounted: List<MenuItem>, bogoPrice: Double? = null): Double {
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
            DealKind.BOGO -> if (persons >= 2 && minOk) (bogoPrice ?: mp) * (persons / 2) else 0.0
            DealKind.HALF_SECOND -> if (persons >= 2) (bogoPrice ?: mp) * 0.5 * (persons / 2) else 0.0
            DealKind.FREE_ITEM -> if (minOk) 3.5 else 0.0
            DealKind.FREE_DELIVERY -> if (minOk) fee else 0.0
            DealKind.FIXED_VALUE -> if (persons >= 2 || d.label.lowercase().let { !it.contains("2für1") && !it.contains("2 für 1") }) d.amount ?: 0.0 else (d.amount ?: 0.0) / 2
            else -> 0.0
        }
    }
}

fun eur(v: Double): String = String.format(java.util.Locale.GERMANY, "%,.2f €", v)
