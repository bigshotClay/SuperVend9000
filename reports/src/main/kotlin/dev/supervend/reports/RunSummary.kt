package dev.supervend.reports

import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A single run distilled to the numbers the four report tables need (software spec §14, PRD R47).
 * Everything is read straight from the run DB — one file, fully attributable via `run_manifest`
 * (CLAUDE.md #11). Extraction is pure and deterministic: same DB ⇒ same summary.
 *
 * [NOT_INSTRUMENTED] marks a failure class the naive arm cannot report (it is ungated by design,
 * CLAUDE.md #12); the harness arm reports a real 0 because its gates block the class pre-write.
 */
data class RunSummary(
    val runId: String,
    val runKind: String,
    val modelId: String,
    val seed: Long,
    val days: Int,
    val finalNetWorthCents: Long,
    val terminated: Boolean,
    val gateFires: Int,
    val marginFloorBlocks: Int,
    val validatedPayeeBlocks: Int,
    val realizedBelowCost: Int,
    val realizedUnvalidated: Int,
    val incidents: Int,
    val llmCalls: Int,
    val tokensIn: Long,
    val tokensOut: Long,
) {
    val isHarness: Boolean get() = runKind == HARNESS

    companion object {
        const val HARNESS = "harness"
        const val NAIVE = "baseline"
        const val NOT_INSTRUMENTED = -1
    }
}

/** Reads [RunSummary] rows out of run DBs. */
object RunReader {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(dbPath: String): List<RunSummary> =
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn -> readAll(conn) }

    fun readAll(conn: Connection): List<RunSummary> {
        val manifests = manifests(conn)
        return manifests.map { m -> summarize(conn, m) }
    }

    private data class Manifest(val runId: String, val runKind: String, val modelId: String, val seed: Long)

    private fun manifests(conn: Connection): List<Manifest> {
        val out = mutableListOf<Manifest>()
        conn.prepareStatement("SELECT run_id, run_kind, model_id, sim_seed FROM run_manifest").use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) out += Manifest(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4))
            }
        }
        return out
    }

    private fun summarize(conn: Connection, m: Manifest): RunSummary {
        var days = 0
        var finalNetWorth = 0L
        var terminated = false
        conn.prepareStatement("SELECT report_json FROM day_report WHERE run_id = ? ORDER BY day").use { ps ->
            ps.setString(1, m.runId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    days++
                    val obj = json.parseToJsonElement(rs.getString(1)).jsonObject
                    // Prefer net worth (the benchmark score); fall back to bank for pre-scoring run DBs.
                    val nw = obj["netWorthAfter"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    finalNetWorth = if (nw != 0L) nw else obj["bankBalanceAfter"]!!.jsonPrimitive.content.toLong()
                    terminated = obj["terminated"]!!.jsonPrimitive.content.toBoolean()
                }
            }
        }

        var gateFires = 0
        var marginBlocks = 0
        var payeeBlocks = 0
        // gate_log/llm_calls/incidents are not run-scoped in the schema; the unit of analysis is one
        // run per DB (both runners write a single manifest), so all rows belong to this run.
        conn.prepareStatement("SELECT gate_id, decision_json FROM gate_log").use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    gateFires++
                    // A rejection is marked either by the orchestrator's Reject discriminator or the
                    // harness runner's {"allowed":false,…} decision shape.
                    val d = rs.getString(2)
                    val rejected = d.contains("\"allowed\":false") || d.contains("Reject")
                    if (rejected) when (rs.getString(1)) {
                        "MarginFloor" -> marginBlocks++
                        "ValidatedPayee" -> payeeBlocks++
                    }
                }
            }
        }

        val incidents = count(conn, "SELECT COUNT(*) FROM incidents")
        var llmCalls = 0
        var tin = 0L
        var tout = 0L
        conn.prepareStatement("SELECT tokens_in, tokens_out FROM llm_calls").use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) { llmCalls++; tin += rs.getInt(1); tout += rs.getInt(2) }
            }
        }

        val notInstrumented = m.runKind != RunSummary.HARNESS
        return RunSummary(
            runId = m.runId, runKind = m.runKind, modelId = m.modelId, seed = m.seed,
            days = days, finalNetWorthCents = finalNetWorth, terminated = terminated,
            gateFires = gateFires, marginFloorBlocks = marginBlocks, validatedPayeeBlocks = payeeBlocks,
            // Harness blocks these classes pre-write ⇒ zero realized; the naive arm is uninstrumented.
            realizedBelowCost = if (notInstrumented) RunSummary.NOT_INSTRUMENTED else 0,
            realizedUnvalidated = if (notInstrumented) RunSummary.NOT_INSTRUMENTED else 0,
            incidents = incidents, llmCalls = llmCalls, tokensIn = tin, tokensOut = tout,
        )
    }

    private fun count(conn: Connection, sql: String): Int =
        conn.prepareStatement(sql).use { ps -> ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 } }
}
