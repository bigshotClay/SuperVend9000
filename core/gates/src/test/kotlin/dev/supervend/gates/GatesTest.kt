package dev.supervend.gates

import dev.supervend.model.ActionType
import dev.supervend.model.BasisPoints
import dev.supervend.model.Cents
import dev.supervend.model.GateDecision
import dev.supervend.model.ProductId
import dev.supervend.model.ProposedAction
import dev.supervend.model.QuoteId
import dev.supervend.model.QuoteRef
import dev.supervend.model.SanityBand
import dev.supervend.model.SupplierId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

private val COLA = ProductId("COLA")
private val SUP = SupplierId("SUP-HC")

private fun allowed(d: GateDecision) = d is GateDecision.Allow
private fun rejected(d: GateDecision) = d is GateDecision.Reject

class GatesTest : FunSpec({

    val cfg = GateConfig.default()

    // ---- R18 MarginFloor ----
    test("R18: any price below cost×(1+floor) is rejected; at/above is allowed") {
        val gate = MarginFloorGate(cfg)
        checkAll(Arb.long(1L..1000L), Arb.long(0L..3000L)) { cost, delta ->
            val state = TestStateView(costBases = mapOf(COLA to Cents(cost)))
            val floorPrice = Cents(cost).scaleBps(BasisPoints(10_000 + cfg.marginFloorBps.value))
            val price = Cents(floorPrice.value + delta)
            allowed(gate.evaluate(ProposedAction.SetPrice(COLA, price), state)) shouldBe true
            if (floorPrice.value > 0) {
                val below = Cents(floorPrice.value - 1)
                rejected(gate.evaluate(ProposedAction.SetPrice(COLA, below), state)) shouldBe true
            }
        }
    }

    // ---- R19 ValidatedPayee ----
    test("R19: orders to unvalidated payees are rejected") {
        val gate = ValidatedPayeeGate()
        val order = ProposedAction.PlaceOrder(SUP, COLA, 10, Cents(50))
        rejected(gate.evaluate(order, TestStateView())) shouldBe true
        allowed(gate.evaluate(order, TestStateView(validatedSuppliers = setOf(SUP)))) shouldBe true
    }

    // ---- R20 SpecBeforePay ----
    test("R20: orders without an order spec on file are rejected") {
        val gate = SpecBeforePayGate()
        val order = ProposedAction.PlaceOrder(SUP, COLA, 10, Cents(50))
        rejected(gate.evaluate(order, TestStateView())) shouldBe true
        allowed(gate.evaluate(order, TestStateView(specs = setOf(SUP to COLA)))) shouldBe true
    }

    // ---- R21 SpendCap ----
    test("R21: single-order and daily caps both reject") {
        val gate = SpendCapGate(cfg)
        // Over single-order cap.
        val big = ProposedAction.PlaceOrder(SUP, COLA, units = 1, unitCost = Cents(cfg.spendCapPerOrder.value + 1))
        rejected(gate.evaluate(big, TestStateView())) shouldBe true
        // Within single-order but pushes the day over the daily cap.
        val ok = ProposedAction.PlaceOrder(SUP, COLA, units = 1, unitCost = Cents(cfg.spendCapPerOrder.value))
        val nearDailyLimit = TestStateView(spentToday = Cents(cfg.spendCapPerDay.value - cfg.spendCapPerOrder.value + 1))
        rejected(gate.evaluate(ok, nearDailyLimit)) shouldBe true
        allowed(gate.evaluate(ok, TestStateView())) shouldBe true
    }

    // ---- R22 ConcessionPolicy (social engineering lives in the gate) ----
    test("R22: any concession above the ceiling is rejected unconditionally") {
        val gate = ConcessionPolicyGate(cfg)
        checkAll(Arb.int(0..2000)) { bps ->
            val d = gate.evaluate(ProposedAction.ConcessionOffer("s", BasisPoints(bps.toLong())), TestStateView())
            (bps.toLong() <= cfg.concessionCeilingBps.value) shouldBe allowed(d)
        }
    }

    // ---- R23 QuoteSanity: band + MAD ----
    test("R23: quote outside the researched band is rejected") {
        val gate = QuoteSanityGate(cfg)
        val band = SanityBand(Cents(40), Cents(80))
        val state = TestStateView(bands = mapOf(COLA to band))
        val outside = ProposedAction.AcceptQuote(QuoteRef(QuoteId("q")), COLA, Cents(120), emptyList())
        rejected(gate.evaluate(outside, state)) shouldBe true
        val inside = ProposedAction.AcceptQuote(QuoteRef(QuoteId("q")), COLA, Cents(60), emptyList())
        allowed(gate.evaluate(inside, state)) shouldBe true
    }

    test("R23: a MAD outlier within the band is still rejected") {
        val gate = QuoteSanityGate(cfg)
        val band = SanityBand(Cents(0), Cents(1000))
        val state = TestStateView(bands = mapOf(COLA to band))
        // Parallel quotes tightly clustered around 50; an accept at 500 is a gross outlier.
        val parallel = listOf(Cents(48), Cents(50), Cents(52), Cents(49), Cents(51))
        val outlier = ProposedAction.AcceptQuote(QuoteRef(QuoteId("q")), COLA, Cents(500), parallel)
        rejected(gate.evaluate(outlier, state)) shouldBe true
        val normal = ProposedAction.AcceptQuote(QuoteRef(QuoteId("q")), COLA, Cents(50), parallel)
        allowed(gate.evaluate(normal, state)) shouldBe true
    }

    // ---- R24 RecoveryWrite ----
    test("R24: recovery writes that move a balance or overwrite are rejected") {
        val gate = RecoveryWriteGate()
        allowed(gate.evaluate(ProposedAction.RecoveryWrite(movesBalance = false, overwrites = false, annotationOnly = true), TestStateView())) shouldBe true
        rejected(gate.evaluate(ProposedAction.RecoveryWrite(movesBalance = true, overwrites = false, annotationOnly = true), TestStateView())) shouldBe true
        rejected(gate.evaluate(ProposedAction.RecoveryWrite(movesBalance = false, overwrites = true, annotationOnly = true), TestStateView())) shouldBe true
        rejected(gate.evaluate(ProposedAction.RecoveryWrite(movesBalance = false, overwrites = false, annotationOnly = false), TestStateView())) shouldBe true
    }

    // ---- LedgerConsistency (workplan §6.2, OPERATOR-INPUT #10) ----
    test("ledger mirror-write must equal its source tool result") {
        val gate = LedgerConsistencyGate()
        val ok = ProposedAction.WriteLedger(ActionType.WRITE_CASH_LEDGER, claimed = 1234, source = 1234)
        allowed(gate.evaluate(ok, TestStateView())) shouldBe true
        val fudged = ProposedAction.WriteLedger(ActionType.WRITE_CASH_LEDGER, claimed = 9999, source = 1234)
        rejected(gate.evaluate(fudged, TestStateView())) shouldBe true
    }

    // ---- R25 TaskMandate ----
    test("R25: tasks failing schema or mandate are rejected") {
        val gate = TaskMandateGate()
        allowed(gate.evaluate(ProposedAction.SubmitTask("k", schemaValid = true, withinMandate = true), TestStateView())) shouldBe true
        rejected(gate.evaluate(ProposedAction.SubmitTask("k", schemaValid = false, withinMandate = true), TestStateView())) shouldBe true
        rejected(gate.evaluate(ProposedAction.SubmitTask("k", schemaValid = true, withinMandate = false), TestStateView())) shouldBe true
    }

    // ---- Registry + reject payload ----
    test("registry resolves all nine gate ids and config is content-addressed") {
        val reg = GateRegistry(cfg)
        reg.ids.map { it.value }.toSet() shouldBe setOf(
            "MarginFloor", "ValidatedPayee", "SpecBeforePay", "SpendCap",
            "ConcessionPolicy", "QuoteSanity", "RecoveryWrite", "LedgerConsistency", "TaskMandate",
        )
        GateConfig.default().hash() shouldBe GateConfig.default().hash()
    }

    // ---- R1a ablation: pass-through registry ----
    test("passthrough registry resolves the same ids but every gate allows") {
        val real = GateRegistry(cfg)
        val off = GateRegistry.passthrough(cfg)
        // Same ids so every plan-declared gate still resolves via require().
        off.ids shouldBe real.ids
        // A below-floor price the real MarginFloor rejects is Allowed by the passthrough.
        val belowFloor = ProposedAction.SetPrice(COLA, Cents(1))
        val state = TestStateView(costBases = mapOf(COLA to Cents(100)))
        val floorId = MarginFloorGate(cfg).id
        rejected(real.require(floorId).evaluate(belowFloor, state)) shouldBe true
        allowed(off.require(floorId).evaluate(belowFloor, state)) shouldBe true
        // Every gate in the passthrough allows regardless of action/state.
        val order = ProposedAction.PlaceOrder(SUP, COLA, 10, Cents(1))
        off.ids.forEach { id -> allowed(off.require(id).evaluate(order, TestStateView())) shouldBe true }
    }

    test("a rejection carries expected vs observed for retry context") {
        val d = MarginFloorGate(cfg).evaluate(
            ProposedAction.SetPrice(COLA, Cents(1)),
            TestStateView(costBases = mapOf(COLA to Cents(100))),
        )
        val reject = d.shouldBeInstanceOf<GateDecision.Reject>()
        (reject.expected.isNotBlank() && reject.observed.isNotBlank()) shouldBe true
    }
})
