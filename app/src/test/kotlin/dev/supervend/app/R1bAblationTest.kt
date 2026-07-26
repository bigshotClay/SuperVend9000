package dev.supervend.app

import dev.supervend.app.harness.FullRunner
import dev.supervend.llm.CompletionResult
import dev.supervend.llm.MockModel
import dev.supervend.llm.Usage
import dev.supervend.sim.SimConfig
import dev.supervend.state.RunDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.sql.Connection

/**
 * R1b ablation golden run (OPERATOR-INPUT #23). A [MockModel] routes by creed so each seat gets
 * valid JSON: the state-maintainer ("bookkeeper") returns a belief, the pricing seat returns prices.
 * The run must be deterministic, complete the horizon, drive pricing from the belief, and — the
 * budget-parity invariant — issue exactly one `state-maintain` call per day and NO `scribe` call
 * (state-maintain repurposes the daily scribe, so R1b and the full arm make the same number of calls).
 */
class R1bAblationTest : FunSpec({

    val config = SimConfig.default()
    val products = config.products.map { it.id.value }

    // A price for every product, comfortably above the margin floor so proposals apply.
    val pricingJson = """{"prices":[""" +
        products.joinToString(",") { """{"product":"$it","priceCents":200}""" } + "]}"
    // A believed record for every product.
    val beliefJson = """{"products":[""" +
        products.joinToString(",") { """{"product":"$it","onHand":10,"inTransit":0,"unitCostCents":50,"lastPriceCents":150,"note":"steady"}""" } +
        "]}"

    fun model() = MockModel("mock") { req ->
        val text = when {
            req.prompt.contains("bookkeeper") -> beliefJson       // state-maintain seat
            req.prompt.contains("pricing analyst") -> pricingJson // pricing seat
            else -> "{}"                                          // procure-classify / audit → unusable-ish
        }
        CompletionResult.Completed(text, Usage(tokensIn = req.prompt.length / 4, tokensOut = text.length, latencyMs = 0, httpStatus = 200))
    }

    fun runOnce(conn: Connection): FullRunner {
        val runner = FullRunner(model(), "mock", config = config, externalizeState = false)
        runBlocking { runner.run("r1b-1", seed = 7, days = 21, conn) }
        return runner
    }

    test("R1b run is deterministic, completes, and prices from the model's belief") {
        val a = RunDatabase.inMemory().use { db -> runOnce(db.connection).stats }
        val b = RunDatabase.inMemory().use { db -> runOnce(db.connection).stats }
        a shouldBe b                       // bit-reproducible
        a.priceApplied shouldBeGreaterThan 0 // belief → pricing seat → gated apply
    }

    test("budget parity: one state-maintain call per day, no scribe call") {
        RunDatabase.inMemory().use { db ->
            runOnce(db.connection)
            fun count(step: String): Int = db.connection.prepareStatement(
                "SELECT COUNT(*) FROM llm_calls WHERE step_id = ?",
            ).use { ps -> ps.setString(1, step); ps.executeQuery().use { it.next(); it.getInt(1) } }
            count("state-maintain") shouldBe 21   // once per day
            count("scribe") shouldBe 0            // repurposed, not additional
            (count("pricing") == 21).shouldBeTrue()
            db.connection.prepareStatement("SELECT run_kind FROM run_manifest").use { ps ->
                ps.executeQuery().use { it.next(); it.getString(1) shouldBe "harness-R1b-nostate" }
            }
        }
    }
})
