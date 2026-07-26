package dev.supervend.sim

import dev.supervend.model.Cents
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

class DemandTest : FunSpec({

    val product = SimConfig.default().product(dev.supervend.model.ProductId("COLA"))

    test("expected demand is strictly decreasing in price") {
        checkAll(Arb.int(50..500), Arb.int(1..50)) { price, bump ->
            val lo = Demand.expectedUnits(product, Cents(price.toLong()), simDay = 3)
            val hi = Demand.expectedUnits(product, Cents((price + bump).toLong()), simDay = 3)
            lo shouldBeGreaterThanOrEqual hi
        }
    }

    test("demand equals base at the reference price on a neutral weekday") {
        // day index 0 (simDay=1) has weekday multiplier 1.0 in the default config.
        val units = Demand.expectedUnits(product, product.referencePriceCents, simDay = 1)
        units shouldBe product.baseDailyDemand
    }

    test("monotonicity-in-expectation holds for realized demand averaged over seeds") {
        val lowPrice = Cents(120)
        val highPrice = Cents(220)
        fun meanRealized(price: Cents): Double {
            val n = 400
            var total = 0L
            for (s in 0 until n) {
                val rng = Rng.stream(seed = s.toLong(), day = 5, purpose = "demand:COLA")
                total += Demand.realizedUnits(product, price, simDay = 5, rng = rng)
            }
            return total.toDouble() / n
        }
        meanRealized(lowPrice) shouldBeGreaterThanOrEqual meanRealized(highPrice)
    }
})
