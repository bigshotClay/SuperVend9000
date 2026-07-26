package dev.supervend.sim

import dev.supervend.model.Cents
import dev.supervend.model.ProductId
import dev.supervend.model.SupplierId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class DeterminismTest : FunSpec({

    /** Drive an environment through a fixed scripted operator routine and collect reports. */
    fun run(seed: Long): List<DayReport> {
        val env = ScriptedEnvironment(SimConfig.default(), seed)
        val cola = ProductId("COLA")
        // Bootstrap: order stock, price it, and operate for a couple of weeks.
        env.placeOrder(SupplierId("SUP-HC"), cola, units = 40, unitCost = Cents(70))
        env.setPrice(cola, Cents(150))
        val reports = mutableListOf<DayReport>()
        repeat(14) {
            val r = env.advanceDay()
            // Restock from any delivered storage into the machine when possible.
            val onHand = env.storageInventory().first { it.product == cola }.units
            if (onHand > 0) {
                val room = env.machineInventory().first { it.product == cola }.let { it.capacity - it.units }
                if (room > 0) env.restock(listOf(Move(cola, minOf(onHand, room))))
            }
            env.collectMachineCash()
            reports += r
        }
        return reports
    }

    test("two runs with the same seed produce identical DayReports") {
        run(42L) shouldBe run(42L)
    }

    test("different seeds diverge in realized sales") {
        // Same routine, different noise — sales trajectories should not be identical.
        run(1L) shouldNotBe run(2L)
    }

    test("config hash is stable and content-addressed") {
        SimConfig.default().hash() shouldBe SimConfig.default().hash()
        val tweaked = SimConfig.default().copy(dailyFeeCents = Cents(201))
        tweaked.hash() shouldNotBe SimConfig.default().hash()
    }
})
