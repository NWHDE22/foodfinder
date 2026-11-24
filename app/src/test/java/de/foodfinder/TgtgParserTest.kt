package de.foodfinder

import de.foodfinder.notify.TgtgScreenParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Card texts as the TGTG app exposes them to accessibility (synthetic examples). */
class TgtgParserTest {
    @Test fun listCard() {
        val b = TgtgScreenParser.toBag(listOf("3 übrig", "Bäckerei Musterkorn - Mitte", "Überraschungstüte", "Heute abholen 18:00 - 18:30", "4,6", "0,8 km", "12,00 €", "3,99 €"), 1L)!!
        assertEquals("Bäckerei Musterkorn - Mitte", b.name)
        assertEquals(3.99, b.price, 0.001); assertEquals(12.0, b.value!!, 0.001)
        assertEquals(4.6, b.rating!!, 0.001); assertEquals("18:00–18:30", b.pickupWindow); assertEquals(3, b.itemsLeft); assertEquals(800, b.distanceM)
    }
    @Test fun soldOutAndSinglePrice() {
        val b = TgtgScreenParser.toBag(listOf("Ausverkauft", "Sushi Beispiel", "Morgen abholen 20:30 – 21:00", "€4.50"), 1L)!!
        assertEquals(0, b.itemsLeft); assertNull(b.value); assertEquals(4.5, b.price, 0.001)
    }
    @Test fun notACard() { assertNull(TgtgScreenParser.toBag(listOf("Entdecken", "Favoriten"), 1L)) }
}
