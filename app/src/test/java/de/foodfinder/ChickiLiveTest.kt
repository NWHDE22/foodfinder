package de.foodfinder

import de.foodfinder.data.MembershipState
import de.foodfinder.data.Mode
import de.foodfinder.data.Savings
import de.foodfinder.data.scopeText
import de.foodfinder.data.sources.LieferandoSource
import de.foodfinder.data.sources.Where
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Manual live check with the restaurant from a user report (public data, city centre coordinates). */
class ChickiLiveTest {
    @Test fun chicki() = runBlocking {
        val v0 = LieferandoSource.list(Mode.DELIVERY, Where(51.4566, 7.0116, "Essen")).first { it.key == "chicki-the-kiki" }
        val v = LieferandoSource.details(v0) ?: v0
        val menu = LieferandoSource.menu(v)
        v.deals.forEach { println("DEAL ${it.kind} | ${it.scopeText()}") }
        println("MENU gifts=${menu.filter { "GIFT" in it.labels }.map { it.name }} qual=${menu.filter { "QUALIFIER" in it.labels }.map { it.name }} disc=${menu.filter { it.discounted }.map { it.name + " " + it.price + "/" + it.originalPrice }}")
        val e = Savings.estimate(v, 2, MembershipState(), emptyList(), menu)
        e.lines.forEach { println("LINE ${it.first} = ${"%.2f".format(it.second)}") }
        println("RESULT saving=${"%.2f".format(e.savings)} total=${"%.2f".format(e.total)} notes=${e.notes}")
    }
}
