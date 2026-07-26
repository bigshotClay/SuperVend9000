package dev.supervend.sim

import dev.supervend.model.Cents
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The amoral rational vendor: concede when hungry, exploit when depended-on, price to our ceiling. */
class RationalVendorTest : FunSpec({

    val product = SimConfig.hardMode().products.first() // COLA: wholesale 60, reference 150
    val vendor = SupplierProfile.Rational(floorMult = 1.10, greed = 0.5, deliveryCut = 0.4)
    val buyerFloorBps = 2500L
    fun quote(exploit: Double, round: Int = 0): Long =
        when (val r = SupplierEngine.reply(vendor, product, round, day = 1, exploitation = exploit, buyerMarginFloorBps = buyerFloorBps)) {
            is SupplierEngine.Reply.Quote -> r.unitCost.value
            is SupplierEngine.Reply.Counter -> r.unitCost.value
            else -> error("expected a quote")
        }

    test("hungry (dropped) → quotes near its floor; depended-on → pushes to our ceiling") {
        val floor = (product.wholesaleCostCents.value * 1.10).toLong()          // 66
        val walkAway = product.referencePriceCents.value * 10_000 / 12_500      // 120 (150 / 1.25)
        val cap = walkAway * 92 / 100                                           // rational: leaves 8% buyer surplus
        quote(exploit = 0.0) shouldBe floor          // hungry → best deal
        quote(exploit = 1.0) shouldBe cap            // fully depended-on → just below the buyer's walk-away
        (quote(exploit = 0.5) > floor) shouldBe true
    }

    test("negotiation concedes a depended-on vendor back down") {
        val opening = quote(exploit = 1.0, round = 0)
        val afterCounter = quote(exploit = 1.0, round = 2)
        (afterCounter < opening) shouldBe true
    }

    test("delivery is full when hungry, cut when depended-on") {
        val hungry = SupplierEngine.scheduleDelivery(vendor, units = 100, day = 1, defaultLagDays = 3, exploitation = 0.0)!!
        val hooked = SupplierEngine.scheduleDelivery(vendor, units = 100, day = 1, defaultLagDays = 3, exploitation = 1.0)!!
        hungry.units shouldBe 100
        (hooked.units < 100) shouldBe true // cut by up to deliveryCut (40%) → 60
    }

    test("exploitation resets to zero once the buyer goes quiet past the cool gap") {
        SupplierEngine.exploitation(vendor, orderStreak = 5, daysSinceLastOrder = 100, coolGap = 8) shouldBe 0.0
        (SupplierEngine.exploitation(vendor, orderStreak = 5, daysSinceLastOrder = 1, coolGap = 8) > 0.0) shouldBe true
    }
})
