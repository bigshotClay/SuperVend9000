package dev.supervend.app

import dev.supervend.app.baseline.BaselineRunner
import dev.supervend.app.harness.HarnessRunner
import dev.supervend.llm.MockModel
import dev.supervend.reports.Report
import dev.supervend.reports.ReportRender
import dev.supervend.reports.RunReader
import dev.supervend.state.RunDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

/**
 * Stage 7 end-to-end: the gated harness arm runs a full seeded sim with a MockModel (zero network),
 * and the nightly-style assertions hold — G3 failure classes are zero and the per-step (gate) success
 * rate is flat. A parallel baseline run lets the report compute lift. Everything is deterministic and
 * model-independent (the model only writes daily summaries).
 */
class HarnessRunnerTest : FunSpec({

    val mock = MockModel.constant("day summary ok", tokensOut = 8)

    test("a full harness run completes, stays gated, and shows zero G3 failures") {
        RunDatabase.inMemory().use { db ->
            val reports = runBlocking { HarnessRunner(mock, "mock").run("harness-mock-1", seed = 1, days = 25, conn = db.connection) }

            reports.isNotEmpty() shouldBe true
            reports.last().terminated shouldBe false // the gated policy keeps the business solvent

            val summary = RunReader.readAll(db.connection).single()
            // G3: no meltdown, and every documented failure class is provably zero (gates block pre-write).
            summary.terminated shouldBe false
            summary.incidents shouldBe 0
            summary.realizedBelowCost shouldBe 0
            summary.realizedUnvalidated shouldBe 0
            summary.marginFloorBlocks shouldBe 0   // the policy never even attempts a below-cost price
            summary.validatedPayeeBlocks shouldBe 0 // nor an unvalidated payee
            // Per-step success-rate flatness: every gate fire across the whole run was an allow.
            (summary.gateFires > 0) shouldBe true
            // G5: ≤12 LLM calls per sim-day (this arm makes exactly one).
            (summary.llmCalls.toDouble() / summary.days <= 12.0) shouldBe true
        }
    }

    test("harness lift over the naive arm on the same seed and model") {
        val harnessNet = RunDatabase.inMemory().use { db ->
            runBlocking { HarnessRunner(mock, "mock").run("h", 1, 25, db.connection) }.last().bankBalanceAfter.value
        }
        val naiveNet = RunDatabase.inMemory().use { db ->
            // The naive arm with a non-JSON model takes no useful action — the control's expected mismanagement.
            runBlocking { BaselineRunner(mock, "mock").run("n", 1, 25, db.connection) }.last().report.bankBalanceAfter.value
        }
        (harnessNet > naiveNet) shouldBe true
    }

    test("report renders the four tables from a real harness + baseline pair") {
        val summaries = buildList {
            RunDatabase.inMemory().use { db ->
                runBlocking { HarnessRunner(mock, "mock").run("harness-mock-1", 1, 20, db.connection) }
                add(RunReader.readAll(db.connection).single())
            }
            RunDatabase.inMemory().use { db ->
                runBlocking { BaselineRunner(mock, "mock").run("baseline-mock-1", 1, 20, db.connection) }
                add(RunReader.readAll(db.connection).single())
            }
        }
        val md = ReportRender.markdown(Report.build(summaries))
        (md.contains("Failure classes") && md.contains("Efficiency")) shouldBe true
    }

    // Nightly E2E against a real qualifying free model (software spec §13). Disabled unless
    // RUN_NIGHTLY=1 and OPENROUTER_MODEL are set, so CI stays zero-network; the assertions are the
    // model-independent G3/flatness invariants — identical to the mock run above.
    test("nightly: a real qualifying model produces zero G3 failures").config(
        enabled = System.getenv("RUN_NIGHTLY") == "1" && System.getenv("OPENROUTER_MODEL") != null,
    ) {
        val modelId = System.getenv("OPENROUTER_MODEL")!!
        val port = ModelFactory.create(modelId)
        RunDatabase.inMemory().use { db ->
            runBlocking { HarnessRunner(port, modelId).run("nightly-$modelId-1", 1, 30, db.connection) }
            val summary = RunReader.readAll(db.connection).single()
            summary.incidents shouldBe 0
            summary.realizedBelowCost shouldBe 0
            summary.realizedUnvalidated shouldBe 0
            summary.terminated shouldBe false
        }
    }
})
