package dev.supervend.app

import dev.supervend.app.baseline.BaselineRunner
import dev.supervend.llm.MockModel
import dev.supervend.state.RunDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/** A fixed coherent daily plan — a MockModel that keeps the machine priced and stocked. */
private val PLAN = """
    {"actions":[
      {"type":"PlaceOrder","supplier":"SUP-HC","product":"COLA","units":30,"unitCostCents":70},
      {"type":"SetPrice","product":"COLA","priceCents":150},
      {"type":"Restock","product":"COLA","units":30},
      {"type":"CollectCash"}
    ]}
""".trimIndent()

class BaselineRunnerTest : FunSpec({

    fun rows(db: RunDatabase, sql: String): Int =
        db.connection.createStatement().executeQuery(sql).use { it.next(); it.getInt(1) }

    test("baseline runs a full sim end-to-end and writes a complete run DB (M2 DoD)") {
        RunDatabase.inMemory().use { db ->
            val model = MockModel.constant(PLAN)
            val traces = BaselineRunner(model, "mock:plan").run("run-A", seed = 1L, days = 10, conn = db.connection)
            traces.size shouldBe 10
            traces.all { it.parsedOk }.shouldBeTrue()

            rows(db, "SELECT COUNT(*) FROM run_manifest") shouldBe 1
            rows(db, "SELECT COUNT(*) FROM day_report WHERE run_id='run-A'") shouldBe 10
            rows(db, "SELECT COUNT(*) FROM baseline_action WHERE run_id='run-A'") shouldBe 10
            rows(db, "SELECT COUNT(*) FROM llm_calls") shouldBe 10
            rows(db, "SELECT COUNT(*) FROM cash_ledger") shouldBeGreaterThan 0
        }
    }

    test("a model that emits garbage still completes the run (naive arm has no gates)") {
        RunDatabase.inMemory().use { db ->
            val model = MockModel.constant("I'm not sure what to do today, sorry!")
            val traces = BaselineRunner(model, "mock:garbage").run("run-B", seed = 2L, days = 8, conn = db.connection)
            traces.size shouldBe 8
            traces.none { it.parsedOk }.shouldBeTrue() // nothing parsed, but the sim still advanced
        }
    }

    test("same seed + same model ⇒ identical DayReports (determinism)") {
        suspend fun once(): List<Long> = RunDatabase.inMemory().use { db ->
            BaselineRunner(MockModel.constant(PLAN), "mock:plan")
                .run("r", 42L, 12, db.connection)
                .map { it.report.bankBalanceAfter.value }
        }
        once() shouldBe once()
    }
})
