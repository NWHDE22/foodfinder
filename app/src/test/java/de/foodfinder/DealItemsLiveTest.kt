package de.foodfinder

import de.foodfinder.data.Mode
import de.foodfinder.data.scopeText
import de.foodfinder.data.sources.LieferandoSource
import de.foodfinder.data.sources.UberSource
import de.foodfinder.data.sources.WoltSource
import de.foodfinder.data.sources.Where
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Live check (manual): do deals name their products? */
class DealItemsLiveTest {
    private val at = Where(52.52, 13.405, "Berlin")

    @Test fun lieferando() = runBlocking {
        val l = LieferandoSource.list(Mode.DELIVERY, at).filter { !it.isGrocery && it.deals.any { d -> d.kind.name in setOf("BOGO", "ITEM_PERCENT") } }.take(4)
        for (v in l) {
            val d = LieferandoSource.details(v) ?: v
            val m = LieferandoSource.menu(v)
            println("LIEF ${v.name} fees=${v.feeBands}")
            d.deals.take(4).forEach { println("   ${it.label} -> ${it.scopeText()}") }
            println("   menu disc=${m.count { it.discounted }} bogo=${m.count { "BOGO" in it.labels }}")
        }
    }
    @Test fun uber() = runBlocking {
        val v = UberSource.list(Mode.DELIVERY, at).first { it.deals.any { d -> d.kind.name == "BOGO" } }
        val m = UberSource.menu(v)
        println("UBER ${v.name}: bogo=${m.filter { "BOGO" in it.labels }.map { it.name }.take(4)} disc=${m.count { it.discounted }}")
    }
    @Test fun wolt() = runBlocking {
        WoltSource.list(Mode.DELIVERY, at).filter { !it.isGrocery }.take(8).forEach { v ->
            val d = WoltSource.details(v) ?: return@forEach
            val real = d.deals.filter { it.kind.name != "OTHER" }
            if (real.isNotEmpty()) println("WOLT ${v.name}: ${real.map { it.label + " -> " + it.scopeText() }} disc=${WoltSource.menu(v).count { it.discounted }}")
        }
    }
}
