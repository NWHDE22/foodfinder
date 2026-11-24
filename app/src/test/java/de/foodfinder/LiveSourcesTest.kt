package de.foodfinder

import de.foodfinder.data.Filters
import de.foodfinder.data.MembershipState
import de.foodfinder.data.Mode
import de.foodfinder.data.Ranked
import de.foodfinder.data.Savings
import de.foodfinder.data.Venue
import de.foodfinder.data.eur
import de.foodfinder.data.sources.LieferandoSource
import de.foodfinder.data.sources.NeoTasteSource
import de.foodfinder.data.sources.Source
import de.foodfinder.data.sources.UberSource
import de.foodfinder.data.sources.WoltSource
import de.foodfinder.data.sources.Where
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Hits the real endpoints (generic city-centre coordinates). Run manually: gradlew testDebugUnitTest --tests *LiveSourcesTest* */
class LiveSourcesTest {
    private val at = Where(52.52, 13.405, "Berlin")

    private fun run(src: Source, mode: Mode) = runBlocking {
        val t = System.currentTimeMillis()
        val r = runCatching { src.list(mode, at) }
        val list = r.getOrElse { println("${src.service} $mode FAILED: $it"); return@runBlocking emptyList<Venue>() }
        println("${src.service} $mode: ${list.size} venues, ${list.count { it.deals.isNotEmpty() }} with deals (${System.currentTimeMillis() - t} ms)")
        val ranked = list.filter { it.isOpen && !it.isGrocery }.map { Ranked(it, Savings.estimate(it, 2, MembershipState(), emptyList())) }
            .sortedByDescending { it.estimate.savings }
        ranked.take(4).forEach { println("   ${it.venue.name} | spart ${eur(it.estimate.savings)} | 2P ${eur(it.estimate.total)} | ${it.estimate.appliedDeals.joinToString { d -> d.label }} | fee=${it.venue.deliveryFee} mbw=${it.venue.minOrder} eta=${it.venue.etaMin}-${it.venue.etaMax} cui=${it.venue.cuisines.take(3)}") }
        list
    }

    @Test fun lieferando() { val l = run(LieferandoSource, Mode.DELIVERY); run(LieferandoSource, Mode.PICKUP)
        val v = l.firstOrNull { it.deals.isNotEmpty() && !it.isGrocery } ?: return
        runBlocking { val m = runCatching { LieferandoSource.menu(v) }; println("  menu ${v.name}: ${m.getOrNull()?.size} items, disc=${m.getOrNull()?.count { it.discounted }} ${m.exceptionOrNull() ?: ""}") } }
    @Test fun wolt() { val l = run(WoltSource, Mode.DELIVERY)
        runBlocking { l.filter { !it.isGrocery }.take(6).forEach { v -> val d = runCatching { WoltSource.details(v) }; println("  wolt details ${v.name}: fee=${d.getOrNull()?.deliveryFee} mbw=${d.getOrNull()?.minOrder} plus=${d.getOrNull()?.plusEligible} deals=${d.getOrNull()?.deals?.map { it.label }} ${d.exceptionOrNull() ?: ""}") } }
        runBlocking { l.filter { !it.isGrocery }.take(25).forEach { v -> val m = runCatching { WoltSource.menu(v) }.getOrNull(); if ((m?.count { it.discounted } ?: 0) > 0) println("  wolt disc ${v.name}: ${m!!.filter { it.discounted }.take(3).map { "${it.name} ${it.price}/${it.originalPrice}" }}") } } }
    @Test fun uber() { val l = run(UberSource, Mode.DELIVERY); run(UberSource, Mode.PICKUP)
        val v = l.firstOrNull { it.deals.any { d -> d.kind.name.startsWith("ITEM") } } ?: return
        runBlocking { val m = runCatching { UberSource.menu(v) }; println("  menu ${v.name}: ${m.getOrNull()?.size} items, disc=${m.getOrNull()?.filter { it.discounted }?.take(3)?.map { "${it.name} ${it.price}/${it.originalPrice}" }} ${m.exceptionOrNull() ?: ""}") } }
    @Test fun neotaste() { run(NeoTasteSource, Mode.PICKUP) }
    @Suppress("unused") private val f = Filters()
}
