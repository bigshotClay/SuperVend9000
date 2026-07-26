package dev.supervend.reports

import dev.supervend.state.RunDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.Connection

/**
 * The report math (software spec §14, PRD G1/G2/G3/G5) over synthetic run DBs — no model, no sim.
 * The four tables are pure functions of the persisted summaries, so the headline claims are asserted
 * directly on crafted data.
 */
class ReportTest : FunSpec({

    fun insertGate(conn: Connection, gate: String, allowed: Boolean) {
        conn.prepareStatement("INSERT INTO gate_log(day, step_id, gate_id, decision_json, payload_sha) VALUES (?,?,?,?,?)").use { ps ->
            ps.setInt(1, 1); ps.setString(2, "s"); ps.setString(3, gate); ps.setString(4, """{"allowed":$allowed,"reason":""}"""); ps.setString(5, "")
            ps.executeUpdate()
        }
    }

    fun insertRun(conn: Connection, runId: String, kind: String, model: String, seed: Long, netWorths: List<Long>, terminated: Boolean = false, marginRejects: Int = 0, payeeRejects: Int = 0, llmPerDay: Int = 1) {
        conn.prepareStatement("INSERT INTO run_manifest(run_id, model_slots_json, plan_hash, gate_config_sha, sim_seed, git_commit, run_kind, model_id, sim_config_sha) VALUES (?,?,?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, runId); ps.setString(2, "[]"); ps.setString(3, "p"); ps.setString(4, "g"); ps.setLong(5, seed); ps.setString(6, ""); ps.setString(7, kind); ps.setString(8, model); ps.setString(9, "s")
            ps.executeUpdate()
        }
        netWorths.forEachIndexed { i, nw ->
            val term = terminated && i == netWorths.lastIndex
            conn.prepareStatement("INSERT INTO day_report(run_id, day, report_json) VALUES (?,?,?)").use { ps ->
                ps.setString(1, runId); ps.setInt(2, i + 1)
                ps.setString(3, """{"day":${i + 1},"unitsSold":{},"revenue":0,"feeCharged":0,"bankBalanceAfter":$nw,"deliveries":[],"consecutiveUnpaidDays":0,"terminated":$term}""")
                ps.executeUpdate()
            }
            repeat(llmPerDay) {
                conn.prepareStatement("INSERT INTO llm_calls(day, step_id, model_id, prompt_sha, tokens_in, tokens_out, latency_ms, http_status) VALUES (?,?,?,?,?,?,?,?)").use { ps ->
                    ps.setInt(1, i + 1); ps.setString(2, "s"); ps.setString(3, model); ps.setString(4, ""); ps.setInt(5, 100); ps.setInt(6, 50); ps.setLong(7, 10); ps.setInt(8, 200)
                    ps.executeUpdate()
                }
            }
        }
        repeat(marginRejects) { insertGate(conn, "MarginFloor", false) }
        repeat(payeeRejects) { insertGate(conn, "ValidatedPayee", false) }
    }

    // One run per DB (production reality: gate_log/llm_calls/incidents are whole-DB, so a run is a file).
    fun runSummary(runId: String, kind: String, model: String, seed: Long, netWorths: List<Long>, terminated: Boolean = false, marginRejects: Int = 0, payeeRejects: Int = 0, llmPerDay: Int = 1): RunSummary =
        RunDatabase.inMemory().use { db ->
            insertRun(db.connection, runId, kind, model, seed, netWorths, terminated, marginRejects, payeeRejects, llmPerDay)
            RunReader.readAll(db.connection).single()
        }

    test("lift, flat curve, failure classes, and efficiency over a two-model matrix") {
        val summaries = buildList {
            // Naive arm: low, noisy net worth; harness arm: high and tight, similar across models.
            for ((s, nw) in listOf(1L to 120L, 2L to 180L, 3L to 90L, 4L to 200L, 5L to 150L)) {
                add(runSummary("naive-A-$s", RunSummary.NAIVE, "model-A", s, List(10) { nw }, terminated = nw < 100))
                add(runSummary("naive-B-$s", RunSummary.NAIVE, "model-B", s, List(10) { nw + 10 }))
            }
            for ((s, nw) in listOf(1L to 1000L, 2L to 1100L, 3L to 900L, 4L to 1050L, 5L to 950L)) {
                add(runSummary("harness-A-$s", RunSummary.HARNESS, "model-A", s, List(10) { nw }))
                add(runSummary("harness-B-$s", RunSummary.HARNESS, "model-B", s, List(10) { nw + 20 }))
            }
        }

        val result = Report.build(summaries)

        // G2 lift: harnessed min > naive mean, per model.
        result.lift.forEach { it.lift shouldBe true }

        // G1 flat curve: CV across model means < CV across seeds (means are ~equal, seeds vary).
        result.flat.flat shouldBe true

        // G3 failure classes: harness configs show zero realized failures and zero meltdowns.
        result.failures.filter { it.config.startsWith("harness/") }.forEach {
            it.meltdowns shouldBe 0
            it.belowCostSales shouldBe 0
            it.unvalidatedPayments shouldBe 0
        }
        // Naive below-cost/unvalidated are not instrumented (ungated arm).
        result.failures.first { it.config.startsWith("baseline/") }.belowCostSales shouldBe RunSummary.NOT_INSTRUMENTED

        // G5 efficiency: ≤12 LLM calls per sim-day.
        result.efficiency.forEach { (it.llmCallsPerDay <= 12.0) shouldBe true }

        val md = ReportRender.markdown(result)
        (md.contains("Harness lift") && md.contains("Flat curve")) shouldBe true
    }

    test("a below-cost gate rejection surfaces as a blocked (not realized) failure") {
        val summary = runSummary("harness-A-1", RunSummary.HARNESS, "model-A", 1, List(5) { 1000L }, marginRejects = 3, payeeRejects = 2)
        summary.marginFloorBlocks shouldBe 3
        summary.validatedPayeeBlocks shouldBe 2
        summary.realizedBelowCost shouldBe 0 // blocked pre-write, never realized
    }
})
