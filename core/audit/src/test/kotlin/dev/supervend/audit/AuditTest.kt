package dev.supervend.audit

import dev.supervend.model.ProductId
import dev.supervend.model.SupplierId
import dev.supervend.plan.Workplan
import dev.supervend.validate.Amendment
import dev.supervend.validate.AmendmentProposal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private val COLA = ProductId("COLA")
private val plan = Workplan.PLAN

class AuditTest : FunSpec({

    // ---- Weekly aggregation (AUD-01) ----
    test("weekly report sums seven days and computes the ambiguous rate in bps") {
        val days = (1..7).map { DailyMetrics(it, revenueCents = 1000, unitsSold = 10, gateFires = 2, incidents = 0, ambiguous = 1, classifications = 10) }
        val report = WeeklyAggregator.aggregate(days)
        report.totalRevenueCents shouldBe 7000
        report.totalUnits shouldBe 70
        report.gateFires shouldBe 14
        report.ambiguousRateBps shouldBe 1000 // 7/70 = 10% = 1000 bps
    }

    test("aggregation requires exactly seven daily summaries") {
        val ex = runCatching { WeeklyAggregator.aggregate(listOf(DailyMetrics(1, 0, 0, 0, 0, 0, 0))) }
        ex.isFailure shouldBe true
    }

    // ---- Amendment bounds (AUD-03) ----
    test("a price-band adjust within bounds is accepted; beyond bounds is rejected") {
        val bounds = AmendmentBounds.default()
        bounds.check(Amendment.PriceBandAdjust(COLA, 1_500)).shouldBeInstanceOf<AmendmentVerdict.Accepted>()
        bounds.check(Amendment.PriceBandAdjust(COLA, 5_000)).shouldBeInstanceOf<AmendmentVerdict.Rejected>()
        bounds.check(Amendment.PriceBandAdjust(COLA, -5_000)).shouldBeInstanceOf<AmendmentVerdict.Rejected>()
    }

    test("bounds config is content-addressed") {
        AmendmentBounds.default().hash() shouldBe AmendmentBounds.default().hash()
    }

    // ---- Plan-version chain (spec §4.4) ----
    test("genesis epoch has no parent") {
        PlanLineage.genesis(plan).version.parentHash.shouldBeNull()
    }

    test("applying a bounded amendment advances the chain with a parent pointer") {
        val genesis = PlanLineage.genesis(plan)
        val proposal = AmendmentProposal(listOf(Amendment.PriceBandAdjust(COLA, 500)))
        val app = PlanLineage.apply(plan, genesis, proposal)

        app.applied shouldHaveSize 1
        app.rejected shouldHaveSize 0
        app.epoch.version.parentHash shouldBe genesis.version.hash
        (app.epoch.version.hash == genesis.version.hash) shouldBe false
        app.epoch.appliedAmendments shouldBe listOf(Amendment.PriceBandAdjust(COLA, 500))
    }

    test("the chain links across multiple audits and is deterministic") {
        val genesis = PlanLineage.genesis(plan)
        val p1 = AmendmentProposal(listOf(Amendment.PriceBandAdjust(COLA, 500)))
        val p2 = AmendmentProposal(listOf(Amendment.SupplierListChange(SupplierId("SUP-2"), add = true)))

        val child = PlanLineage.apply(plan, genesis, p1).epoch
        val grandchild = PlanLineage.apply(plan, child, p2).epoch

        grandchild.version.parentHash shouldBe child.version.hash
        grandchild.appliedAmendments shouldHaveSize 2 // cumulative
        // Same parent + same proposal ⇒ same child hash (content-addressed).
        PlanLineage.apply(plan, genesis, p1).epoch.version.hash shouldBe child.version.hash
    }

    test("an all-rejected proposal does not advance the chain") {
        val genesis = PlanLineage.genesis(plan)
        val bad = AmendmentProposal(listOf(Amendment.PriceBandAdjust(COLA, 9_999)))
        val app = PlanLineage.apply(plan, genesis, bad)

        app.applied shouldHaveSize 0
        app.rejected shouldHaveSize 1
        app.epoch.version.hash shouldBe genesis.version.hash // unchanged — never plugged
    }

    test("the per-audit cap rejects amendments beyond the limit") {
        val genesis = PlanLineage.genesis(plan)
        val many = AmendmentProposal((1..7).map { Amendment.PriceBandAdjust(ProductId("P$it"), 100) })
        val app = PlanLineage.apply(plan, genesis, many, AmendmentBounds(maxPerAudit = 5))
        app.applied shouldHaveSize 5
        app.rejected shouldHaveSize 2
    }
})
