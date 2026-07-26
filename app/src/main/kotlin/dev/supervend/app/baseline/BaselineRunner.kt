package dev.supervend.app.baseline

import dev.supervend.llm.CompletionRequest
import dev.supervend.llm.CompletionResult
import dev.supervend.llm.ModelPort
import dev.supervend.llm.qualify.JsonExtract
import dev.supervend.model.Cents
import dev.supervend.model.Location
import dev.supervend.sim.DayReport
import dev.supervend.sim.EnvironmentPort
import dev.supervend.sim.InboundEmail
import dev.supervend.sim.Move
import dev.supervend.sim.ScriptedEnvironment
import dev.supervend.sim.SimConfig
import dev.supervend.sim.ToolResult
import dev.supervend.state.LedgerSource
import dev.supervend.state.LedgerStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.sql.Connection

/**
 * The honest naive arm (software spec §12, M2). One prompt per day, raw tools, no plan / gates /
 * validation / recovery — it shares only `sim/` and `llm/` (CLAUDE.md #12). It runs a full seeded
 * sim to the horizon (or termination) and writes a complete run DB. Weak models are expected to
 * mismanage the machine here; that mismanagement is the control the harnessed arm is measured
 * against.
 */
class BaselineRunner(
    private val model: ModelPort,
    private val modelId: String,
    private val config: SimConfig = SimConfig.default(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class DayTrace(
        val report: DayReport,
        val rawResponse: String,
        val parsedOk: Boolean,
        val applied: List<String>,
    )

    /** Runs the sim for [days] days (or until terminated). Persists everything to [conn]. */
    suspend fun run(runId: String, seed: Long, days: Int, conn: Connection): List<DayTrace> {
        val env = ScriptedEnvironment(config, seed)
        val ledger = LedgerStore(conn)
        val traces = mutableListOf<DayTrace>()
        // The agent's single running context file — the only thing that carries between days. We
        // supply it and append whatever the agent writes, verbatim; we never edit, summarize, or
        // structure it (that would be the harness's job). It grows unbounded; the model's provider
        // silently truncates it once the day's prompt outgrows the context window — that is the rot.
        val contextFile = StringBuilder()

        writeManifest(conn, runId, seed)
        ensureLedgerTable(conn)

        for (day in 1..days) {
            val inbox = env.pollInbox()
            // Snapshot of what the agent sees this day — captured now, for the forced record below.
            val seen = daySnapshot(env, inbox)
            val prompt = BaselinePrompt.forDay(env, inbox, config.suppliers, contextFile.toString()).render()
            val completion = model.complete(CompletionRequest(modelId, prompt))
            val raw = when (completion) {
                is CompletionResult.Completed -> {
                    recordLlmCall(conn, day, modelId, completion.usage.tokensIn, completion.usage.tokensOut, completion.usage.latencyMs, completion.usage.httpStatus)
                    completion.text
                }
                is CompletionResult.Unusable -> "<unusable:${completion.reason}>"
            }

            val turn = parse(raw)
            val applied = turn?.let { apply(env, it) } ?: emptyList()

            val report = env.advanceDay()

            // FORCE the day's record into the context file — not the agent's choice. This is what
            // makes the file grow for every model, so long-horizon rot is actually tested rather than
            // dodged by an agent that keeps no notes. The agent's own optional note is appended after.
            contextFile.append("\n=== Day ").append(day).append(" ===\n")
            contextFile.append(seen).append('\n')
            contextFile.append("Actions: ").append(if (applied.isEmpty()) "none" else applied.joinToString("; ")).append('\n')
            contextFile.append("Result: ").append(outcomeLine(report))
            turn?.memory?.trim()?.takeIf { it.isNotEmpty() }?.let { contextFile.append("\nNote: ").append(it) }
            saveLedger(conn, runId, day, contextFile.toString())

            mirrorLedger(ledger, report)
            persistDay(conn, runId, report, raw, parsedOk = turn != null, applied)
            traces += DayTrace(report, raw, turn != null, applied)
            if (report.terminated) break
        }
        return traces
    }

    private fun parse(text: String): BaselineTurn? {
        val obj = JsonExtract.firstObject(text) ?: return null
        return runCatching { json.decodeFromJsonElement(BaselineTurn.serializer(), obj) }.getOrNull()
    }

    /** Best-effort application — the naive arm has no gate, so anything the model asks is attempted. */
    private fun apply(env: EnvironmentPort, turn: BaselineTurn): List<String> = turn.actions.map { a ->
        val result: ToolResult = when (a) {
            is BaselineAction.SetPrice -> env.setPrice(dev.supervend.model.ProductId(a.product), Cents(a.priceCents))
            is BaselineAction.Restock -> env.restock(listOf(Move(dev.supervend.model.ProductId(a.product), a.units)))
            is BaselineAction.PlaceOrder -> env.placeOrder(dev.supervend.model.SupplierId(a.supplier), dev.supervend.model.ProductId(a.product), a.units, Cents(a.unitCostCents))
            BaselineAction.CollectCash -> ToolResult.Ok("collected ${env.collectMachineCash().value}c")
            BaselineAction.DoNothing -> ToolResult.Ok("noop")
        }
        "${a::class.simpleName}:${result::class.simpleName}"
    }

    /** Mirror the day's realized cash into the append-only ledger so balances project (E2E proof). */
    private fun mirrorLedger(ledger: LedgerStore, report: DayReport) {
        if (report.revenue.value != 0L) {
            ledger.appendCash(report.day, report.revenue, LedgerSource.SIM_TOOL, "sales day ${report.day}")
        }
        if (report.feeCharged.value != 0L) {
            ledger.appendCash(report.day, Cents(-report.feeCharged.value), LedgerSource.SIM_TOOL, "daily fee day ${report.day}")
        }
        for (d in report.deliveries) {
            ledger.appendInventory(report.day, d.product, Location.STORAGE, d.units, LedgerSource.SIM_TOOL, "delivery ${d.fromOrder}")
        }
    }

    // ---- persistence ----

    private fun writeManifest(conn: Connection, runId: String, seed: Long) {
        conn.prepareStatement(
            "INSERT INTO run_manifest(run_id, model_slots_json, plan_hash, gate_config_sha, sim_seed, git_commit, run_kind, model_id, sim_config_sha) " +
                "VALUES (?,?,?,?,?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, runId)
            ps.setString(2, "[]")
            ps.setString(3, "baseline")
            ps.setString(4, "baseline")
            ps.setLong(5, seed)
            ps.setString(6, "")
            ps.setString(7, "baseline")
            ps.setString(8, modelId)
            ps.setString(9, config.hash().hex)
            ps.executeUpdate()
        }
    }

    private fun persistDay(conn: Connection, runId: String, report: DayReport, raw: String, parsedOk: Boolean, applied: List<String>) {
        conn.prepareStatement("INSERT INTO day_report(run_id, day, report_json) VALUES (?,?,?)").use { ps ->
            ps.setString(1, runId)
            ps.setInt(2, report.day)
            ps.setString(3, Json.encodeToString(DayReport.serializer(), report))
            ps.executeUpdate()
        }
        conn.prepareStatement("INSERT INTO baseline_action(run_id, day, raw_response, parsed_ok, applied_json) VALUES (?,?,?,?,?)").use { ps ->
            ps.setString(1, runId)
            ps.setInt(2, report.day)
            ps.setString(3, raw)
            ps.setInt(4, if (parsedOk) 1 else 0)
            ps.setString(5, Json.encodeToString(ListSerializer(String.serializer()), applied))
            ps.executeUpdate()
        }
    }

    /** Compact record of what the agent observed this day — forced into its context file. */
    private fun daySnapshot(env: EnvironmentPort, inbox: List<InboundEmail>): String {
        val machine = env.machineInventory().joinToString(", ") { "${it.product.value} ${it.units}/${it.capacity}@${it.price.value}c" }
        val storage = env.storageInventory().filter { it.units > 0 }.joinToString(", ") { "${it.product.value} ${it.units}" }.ifEmpty { "empty" }
        val mail = if (inbox.isEmpty()) "none" else inbox.joinToString(" | ") { "${it.from.value}: ${it.body.take(80)}" }
        return "Saw: bank ${env.bankBalance().value}c; machine [$machine]; storage [$storage]; mail [$mail]"
    }

    /** One-line outcome of the resolved day — forced into the context file. */
    private fun outcomeLine(r: DayReport): String {
        val sold = r.unitsSold.entries.filter { it.value > 0 }.joinToString(",") { "${it.key.value}:${it.value}" }.ifEmpty { "nothing" }
        val deliv = if (r.deliveries.isEmpty()) "none" else r.deliveries.joinToString(",") { "${it.product.value}+${it.units}" }
        return "sold $sold; revenue ${r.revenue.value}c; deliveries $deliv; bank ${r.bankBalanceAfter.value}c; net worth ${r.netWorthAfter.value}c"
    }

    /** The agent's running context file, captured as a run artifact — one row per run, overwritten
     *  each day so a killed run still has the ledger through its last completed day. */
    private fun ensureLedgerTable(conn: Connection) {
        conn.createStatement().use { st ->
            st.executeUpdate("CREATE TABLE IF NOT EXISTS baseline_ledger(run_id TEXT PRIMARY KEY, day INTEGER NOT NULL, ledger TEXT NOT NULL)")
        }
    }

    private fun saveLedger(conn: Connection, runId: String, day: Int, ledger: String) {
        conn.prepareStatement("INSERT OR REPLACE INTO baseline_ledger(run_id, day, ledger) VALUES (?,?,?)").use { ps ->
            ps.setString(1, runId)
            ps.setInt(2, day)
            ps.setString(3, ledger)
            ps.executeUpdate()
        }
    }

    private fun recordLlmCall(conn: Connection, day: Int, modelId: String, tin: Int, tout: Int, latency: Long, status: Int) {
        conn.prepareStatement(
            "INSERT INTO llm_calls(day, step_id, model_id, prompt_sha, tokens_in, tokens_out, latency_ms, http_status) VALUES (?,?,?,?,?,?,?,?)",
        ).use { ps ->
            ps.setInt(1, day)
            ps.setString(2, "baseline-turn")
            ps.setString(3, modelId)
            ps.setString(4, "")
            ps.setInt(5, tin)
            ps.setInt(6, tout)
            ps.setLong(7, latency)
            ps.setInt(8, status)
            ps.executeUpdate()
        }
    }
}
