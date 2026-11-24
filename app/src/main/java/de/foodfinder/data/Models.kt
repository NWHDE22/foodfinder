package de.foodfinder.data

import kotlinx.serialization.Serializable

enum class Service(val label: String, val short: String, val packages: List<String>, val keywords: List<String>) {
    LIEFERANDO("Lieferando", "LIEF", listOf("com.yopeso.lieferando", "com.takeaway.android"), listOf("lieferando", "takeaway", "just eat")),
    UBER_EATS("Uber Eats", "UBER", listOf("com.ubercab.eats"), listOf("uber eats", "ubereats", "uber.com")),
    WOLT("Wolt", "WOLT", listOf("com.wolt.android"), listOf("wolt")),
    NEOTASTE("NeoTaste", "NEO", listOf("com.neotaste.app", "com.neotaste.android", "app.neotaste"), listOf("neotaste")),
    TGTG("Too Good To Go", "TGTG", listOf("com.app.tgtg"), listOf("too good to go", "toogoodtogo", "tgtg"));

    companion object {
        fun forPackage(pkg: String): Service? =
            entries.firstOrNull { s -> s.packages.any { pkg.startsWith(it) } }
                ?: entries.firstOrNull { s -> s.keywords.any { pkg.contains(it.replace(" ", ""), ignoreCase = true) } }

        fun forText(text: String): Service? =
            entries.firstOrNull { s -> s.keywords.any { text.contains(it, ignoreCase = true) } }
    }
}

enum class Mode { DELIVERY, PICKUP }

val Mode.servicesFor: List<Service>
    get() = when (this) {
        Mode.DELIVERY -> listOf(Service.LIEFERANDO, Service.UBER_EATS, Service.WOLT)
        Mode.PICKUP -> listOf(Service.LIEFERANDO, Service.WOLT, Service.NEOTASTE, Service.TGTG)
    }

enum class DealKind {
    ORDER_PERCENT, ORDER_AMOUNT, ITEM_PERCENT, ITEM_AMOUNT, BOGO, HALF_SECOND,
    FREE_ITEM, FREE_DELIVERY, STAMP_CARD, SURPRISE_BAG, FIXED_VALUE, OTHER
}

enum class DealOrigin { SERVICE, MEMBERSHIP, EMAIL, PUSH }

@Serializable
data class Deal(
    val kind: DealKind,
    val label: String,
    val percent: Double? = null,
    val amount: Double? = null,
    val minOrder: Double? = null,
    val maxDiscount: Double? = null,
    val origin: DealOrigin = DealOrigin.SERVICE,
    val code: String? = null,
)

@Serializable
data class MenuItem(
    val name: String,
    val price: Double,
    val originalPrice: Double? = null,
    val description: String = "",
    val category: String = "",
) {
    val discounted: Boolean get() = originalPrice != null && originalPrice > price + 0.009
    val saving: Double get() = if (discounted) originalPrice!! - price else 0.0
}

@Serializable
data class Venue(
    val id: String,
    val service: Service,
    val mode: Mode,
    val name: String,
    val cuisines: List<String> = emptyList(),
    val etaMin: Int? = null,
    val etaMax: Int? = null,
    val deliveryFee: Double? = null,
    val minOrder: Double? = null,
    val rating: Double? = null,
    val ratingCount: String? = null,
    val distanceM: Int? = null,
    val priceLevel: Int? = null,
    val deals: List<Deal> = emptyList(),
    val previewItems: List<MenuItem> = emptyList(),
    val url: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val isOpen: Boolean = true,
    val isGrocery: Boolean = false,
    val plusEligible: Boolean? = null,
    val pickupWindow: String? = null,
    val itemsAvailable: Int? = null,
    val bagPrice: Double? = null,
    val bagValue: Double? = null,
    val address: String? = null,
    val key: String = "",
) {
    val uid: String get() = "${service.name}:${mode.name}:$id"
}

@Serializable
data class SavedPlace(val id: String, val name: String, val lat: Double, val lon: Double, val address: String = "")

@Serializable
data class Voucher(
    val service: Service,
    val title: String,
    val deal: Deal?,
    val code: String?,
    val validUntil: String?,
    val receivedAt: Long,
    val origin: DealOrigin,
    val snippet: String = "",
)

@Serializable
data class MembershipState(
    val lieferandoPlus: Boolean = false,
    val lieferandoPlusMin: Double = 12.0,
    val woltPlus: Boolean = false,
    val woltPlusMin: Double = 15.0,
    val woltPlusEnds: Long? = null,
    val lieferandoNote: String = "Noch nicht gesetzt",
    val woltNote: String = "Noch nicht gesetzt",
) {
    fun woltPlusActive(now: Long = System.currentTimeMillis()): Boolean =
        woltPlus && (woltPlusEnds == null || woltPlusEnds > now)
}

@Serializable
data class Filters(
    val cuisines: Set<String> = emptySet(),
    val services: Set<Service> = Service.entries.toSet(),
    val maxEta: Int? = null,
    val maxMinOrder: Double? = null,
    val maxDeliveryFee: Double? = null,
    val minRating: Double? = null,
    val onlyOpen: Boolean = true,
    val onlyWithDeals: Boolean = false,
    val hideGroceries: Boolean = true,
    val sort: SortBy = SortBy.SAVINGS,
)

enum class SortBy(val label: String) { SAVINGS("Ersparnis"), TOTAL("Gesamtpreis"), ETA("Lieferzeit"), DISTANCE("Entfernung"), RATING("Bewertung") }

data class Estimate(
    val persons: Int,
    val mainPrice: Double,
    val subtotal: Double,
    val fees: Double,
    val savings: Double,
    val total: Double,
    val regularTotal: Double,
    val appliedDeals: List<Deal>,
    val lines: List<Pair<String, Double>>,
    val exact: Boolean,
    val notes: List<String>,
    /** ranking key: saving, minus half of what you'd have to add just to reach the minimum order */
    val score: Double = savings,
)

data class Ranked(val venue: Venue, val estimate: Estimate)

data class SourceStatus(val service: Service, val ok: Boolean, val count: Int, val message: String? = null)
