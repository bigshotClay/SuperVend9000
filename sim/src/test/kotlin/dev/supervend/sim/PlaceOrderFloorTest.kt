package dev.supervend.sim

import dev.supervend.model.Cents
import dev.supervend.model.ProductId
import dev.supervend.model.SupplierId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Sim-fairness invariant: placeOrder must refuse any price below the good's real cost (wholesale),
 * so no arm can acquire below-cost stock and inflate net worth via the wholesale inventory valuation.
 * Rejection is honest feedback, not a silent clamp; legitimate (≥ floor) orders are unaffected.
 */
class PlaceOrderFloorTest : FunSpec({

    val supplier = SupplierId("SUP-HC") // ValueVend Wholesale, in the default roster
    val water = ProductId("WATER") // wholesale cost = 30c in SimConfig.default()

    test("order below the wholesale floor is rejected and no money moves") {
        val env = ScriptedEnvironment(SimConfig.default(), seed = 1)
        val before = env.bankBalance()
        val r = env.placeOrder(supplier, water, units = 40, unitCost = Cents(10)) // 10c < 30c floor
        r.shouldBeInstanceOf<ToolResult.Rejected>()
        env.bankBalance() shouldBe before // prepay never happened
    }

    test("order exactly at the floor succeeds") {
        val env = ScriptedEnvironment(SimConfig.default(), seed = 1)
        env.placeOrder(supplier, water, units = 40, unitCost = Cents(30)) // == 30c floor
            .shouldBeInstanceOf<ToolResult.Ok>()
    }

    test("order above the floor succeeds") {
        val env = ScriptedEnvironment(SimConfig.default(), seed = 1)
        env.placeOrder(supplier, water, units = 40, unitCost = Cents(50)) // > floor
            .shouldBeInstanceOf<ToolResult.Ok>()
    }
})
