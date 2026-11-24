package de.foodfinder

import de.foodfinder.data.Deal
import de.foodfinder.data.DealKind
import de.foodfinder.data.MembershipState
import de.foodfinder.data.MenuItem
import de.foodfinder.data.Mode
import de.foodfinder.data.Savings
import de.foodfinder.data.Service
import de.foodfinder.data.Venue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavingsTest {
    private val venue = Venue(id = "1", service = Service.LIEFERANDO, mode = Mode.DELIVERY, name = "Chicki (Testdaten)",
        url = "x", minOrder = 18.0, deliveryFee = 0.0,
        deals = listOf(Deal(DealKind.FREE_ITEM, "Erhalte 1 gratis Chicki Nuggets - 5 Stück, wenn du 1x Chicki Box kaufst",
            items = listOf("Chicki Nuggets - 5 Stück"), requires = listOf("Chicki Box (für 2 Personen)"))))
    private val menu = listOf(
        MenuItem("Chicki Box (für 2 Personen)", 18.99, labels = listOf("QUALIFIER")),
        MenuItem("Chicki Nuggets - 5 Stück", 5.49, labels = listOf("GIFT")),
        MenuItem("Chicken Burger", 9.90),
        MenuItem("Wings Box", 12.50),
    )

    @Test fun qualifyingBoxIsPaidGiftIsTheSaving() {
        val e = Savings.estimate(venue, 2, MembershipState(), emptyList(), menu)
        assertEquals(5.49, e.savings, 0.001)          // only the free nuggets
        assertEquals(18.99, e.total, 0.001)           // the box is paid in full
        assertTrue(e.lines.first().first.startsWith("Chicki Box (für 2 Personen)"))
    }

    @Test fun neverFree() {
        val glitch = menu.map { if (it.name.startsWith("Chicken")) it.copy(price = 0.0, originalPrice = 9.90) else it }
        val e = Savings.estimate(venue.copy(deals = listOf(Deal(DealKind.ITEM_PERCENT, "100 %", percent = 100.0))), 2, MembershipState(), emptyList(), glitch)
        assertTrue("total must stay above zero", e.total > 1.0)
    }
}
