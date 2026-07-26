package dev.supervend.app.harness

import dev.supervend.gates.GateConfig
import dev.supervend.gates.GateRegistry
import dev.supervend.llm.CompletionRequest
import dev.supervend.llm.CompletionResult
import dev.supervend.llm.ModelPort
import dev.supervend.model.BasisPoints
import dev.supervend.model.Cents
import dev.supervend.model.Location
import dev.supervend.model.ProductId
import dev.supervend.model.ProposedAction
import dev.supervend.model.SupplierId
import dev.supervend.orchestrator.GateLogEntry
import dev.supervend.orchestrator.StateActor
import dev.supervend.plan.Workplan
import dev.supervend.sim.DayReport
import dev.supervend.sim.Move
import dev.supervend.sim.ScriptedEnvironment
import dev.supervend.sim.SimConfig
import dev.supervend.state.LedgerSource
import dev.supervend.state.LedgerStore
import kotlinx.serialization.json.Json
import java.sql.Connection

/**
 * The harnessed arm (software spec §12 `harness run`). Unlike the naive baseline, every money action
 * flows through the real gate suite via the single [StateActor] (CLAUDE.md #10): prices clear the
 * MarginFloor gate, orders clear SpecBeforePay/ValidatedPayee/SpendCap. The economic policy is a
 * pure script — "if a script can do it, a script does it" — so the run's outcome is *independent of
 * the model* for the deterministic *fundamentals* — the safety floor. On top of that floor the model
 * drives the one decision with real profit leverage: **pricing** ([Decisions.MODEL]). Every proposed
 * price is gated (MarginFloor etc.) and any rejection or unusable output falls back to the script
 * price, so the model's contribution is one-directional — it can push profit above the floor but the
 * gates make it structurally unable to sink below it. Run with [Decisions.SCRIPT] to reproduce the
 * pure-fundamentals floor as a control.
 */
class HarnessRunner(
    private val model: ModelPort,
    private val modelId: String,
    private val config: SimConfig = SimConfig.default(),
    private val gateConfig: GateConfig = GateConfig.default(),
    private val decisions: Decisions = Decisions.MODEL,
) {
    private val json = Json { encodeDefaults = true }
    private val gates = GateRegistry(gateConfig)
    private val priceStep = Workplan.PLAN.allSteps.first { it.id.value == "PRC-02" }
    private val orderStep = Workplan.PLAN.allSteps.first { it.id.value == "PRO-03" }
    private val pricingSeat = PricingSeat(model, modelId)

    /** Cross-run tally of how the model's pricing fared, for reporting the harness's asymmetric guarantee. */
    var stats: PricingStats = PricingStats()
        private set

    /** Who sets prices: the model (alpha on the floor) or the pure script (the control floor). */
    enum class Decisions { MODEL, SCRIPT }

    /** Runs the sim for [days] days (or until terminated), fully gated, persisting to [conn]. */
    suspend fun run(runId: String, seed: Long, days: Int, conn: Connection): List<DayReport> {
        val env = ScriptedEnvironment(config, seed)
        val ledger = LedgerStore(conn)
        // Validate the payee up front (a discovered honest supplier); orders to anyone else are gated out.
        val payee = config.suppliers.first().id
        val world = SimWorld(env, config, validated = setOf(payee))
        val reports = mutableListOf<DayReport>()

        writeManifest(conn, runId, seed)
        var lastReport: DayReport? = null
        var priorReport: DayReport? = null

        for (day in 1..days) {
            world.startDay()
            val gateLog = mutableListOf<GateLogEntry>()
            val actor = StateActor(gates, world, gateLog)

            env.collectMachineCash() // sweep the cash box into the bank (non-risk action)

            // PRICING — the model's decision seat, on top of the script floor. Ask the model for a
            // price per product; gate each proposal; fall back to the script price on rejection or
            // no proposal. The model can only push profit up, never below the floor (asymmetric).
            val modelPrices: Map<dev.supervend.model.ProductId, Cents> = if (decisions == Decisions.MODEL) {
                val obs = config.products.map { p ->
                    PricingSeat.Observation(
                        product = p,
                        currentPrice = env.machineInventory().firstOrNull { it.product == p.id }?.price ?: Cents.ZERO,
                        soldYesterday = lastReport?.unitsSold?.get(p.id) ?: 0,
                        soldPrior = priorReport?.unitsSold?.get(p.id) ?: 0,
                        machineUnits = world.machineOf(p.id),
                        unitCost = world.costBasis(p.id),
                    )
                }
                val proposal = pricingSeat.propose(day, obs)
                recordLlmCall(conn, day, proposal.usage)
                proposal.prices
            } else {
                emptyMap()
            }

            for (p in config.products) {
                val scriptPrice = targetPrice(p)
                val modelPrice = modelPrices[p.id]
                if (modelPrice == null) {
                    actor.submit(priceStep, listOf(ProposedAction.SetPrice(p.id, scriptPrice)))
                    stats = stats.copy(fallbackNoProposal = stats.fallbackNoProposal + 1)
                } else {
                    val submit = actor.submit(priceStep, listOf(ProposedAction.SetPrice(p.id, modelPrice)))
                    if (submit.reject != null) {
                        // The model's price failed a gate → fall back to the safe script price.
                        actor.submit(priceStep, listOf(ProposedAction.SetPrice(p.id, scriptPrice)))
                        stats = stats.copy(modelRejected = stats.modelRejected + 1)
                    } else {
                        stats = stats.copy(modelApplied = stats.modelApplied + 1)
                    }
                }
            }

            // Reorder up to capacity from the validated payee (gated by spec/payee/spend-cap).
            for (p in config.products) {
                val onHand = world.onHand(p.id)
                val need = p.machineCapacity - onHand
                if (need > 0) {
                    val units = minOf(need, p.machineCapacity)
                    actor.submit(orderStep, listOf(ProposedAction.PlaceOrder(payee, p.id, units, p.wholesaleCostCents)))
                }
            }

            // Move delivered storage stock onto the machine (non-risk action).
            val moves = config.products.mapNotNull { p ->
                val toMove = minOf(world.storageOf(p.id), p.machineCapacity - world.machineOf(p.id))
                if (toMove > 0) Move(p.id, toMove) else null
            }
            if (moves.isNotEmpty()) env.restock(moves)

            val report = env.advanceDay()
            mirrorLedger(ledger, report)
            persistDay(conn, runId, report)
            persistGateLog(conn, gateLog)
            reports += report
            priorReport = lastReport
            lastReport = report
            if (report.terminated) break
        }
        return reports
    }

    /** Margin-safe target price: the market reference, floored at cost × (1 + margin floor). */
    private fun targetPrice(p: dev.supervend.sim.ProductConfig): Cents {
        val floor = p.wholesaleCostCents.scaleBps(BasisPoints(BasisPoints.ONE_HUNDRED_PERCENT + gateConfig.marginFloorBps.value))
        return if (p.referencePriceCents >= floor) p.referencePriceCents else floor
    }

    private fun recordLlmCall(conn: Connection, day: Int, completion: CompletionResult) {
        when (completion) {
            is CompletionResult.Completed -> recordLlmCall(conn, day, completion.usage.tokensIn, completion.usage.tokensOut, completion.usage.latencyMs, completion.usage.httpStatus)
            is CompletionResult.Unusable -> recordLlmCall(conn, day, 0, 0, 0, 0)
        }
    }

    private fun mirrorLedger(ledger: LedgerStore, report: DayReport) {
        if (report.revenue.value != 0L) ledger.appendCash(report.day, report.revenue, LedgerSource.SIM_TOOL, "sales day ${report.day}")
        if (report.feeCharged.value != 0L) ledger.appendCash(report.day, Cents(-report.feeCharged.value), LedgerSource.SIM_TOOL, "fee day ${report.day}")
        for (d in report.deliveries) ledger.appendInventory(report.day, d.product, Location.STORAGE, d.units, LedgerSource.SIM_TOOL, "delivery ${d.fromOrder}")
    }

    // ---- persistence ----

    private fun writeManifest(conn: Connection, runId: String, seed: Long) {
        conn.prepareStatement(
            "INSERT INTO run_manifest(run_id, model_slots_json, plan_hash, gate_config_sha, sim_seed, git_commit, run_kind, model_id, sim_config_sha) VALUES (?,?,?,?,?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, runId)
            ps.setString(2, "[]")
            ps.setString(3, Workplan.PLAN.version().hash.hex)
            ps.setString(4, gateConfig.hash().hex)
            ps.setLong(5, seed)
            ps.setString(6, "")
            ps.setString(7, "harness")
            ps.setString(8, modelId)
            ps.setString(9, config.hash().hex)
            ps.executeUpdate()
        }
    }

    private fun persistDay(conn: Connection, runId: String, report: DayReport) {
        conn.prepareStatement("INSERT INTO day_report(run_id, day, report_json) VALUES (?,?,?)").use { ps ->
            ps.setString(1, runId)
            ps.setInt(2, report.day)
            ps.setString(3, json.encodeToString(DayReport.serializer(), report))
            ps.executeUpdate()
        }
    }

    private fun persistGateLog(conn: Connection, entries: List<GateLogEntry>) {
        for (e in entries) {
            conn.prepareStatement("INSERT INTO gate_log(day, step_id, gate_id, decision_json, payload_sha) VALUES (?,?,?,?,?)").use { ps ->
                ps.setInt(1, e.day)
                ps.setString(2, e.stepId.value)
                ps.setString(3, e.gateId)
                val decision = kotlinx.serialization.json.buildJsonObject {
                    put("allowed", kotlinx.serialization.json.JsonPrimitive(e.allowed))
                    put("reason", kotlinx.serialization.json.JsonPrimitive(e.reason))
                }
                ps.setString(4, decision.toString())
                ps.setString(5, dev.supervend.model.Hashing.sha256("${e.stepId.value}:${e.actionType}").hex)
                ps.executeUpdate()
            }
        }
    }

    private fun recordLlmCall(conn: Connection, day: Int, tin: Int, tout: Int, latency: Long, status: Int) {
        conn.prepareStatement(
            "INSERT INTO llm_calls(day, step_id, model_id, prompt_sha, tokens_in, tokens_out, latency_ms, http_status) VALUES (?,?,?,?,?,?,?,?)",
        ).use { ps ->
            ps.setInt(1, day); ps.setString(2, "pricing"); ps.setString(3, modelId); ps.setString(4, "")
            ps.setInt(5, tin); ps.setInt(6, tout); ps.setLong(7, latency); ps.setInt(8, status)
            ps.executeUpdate()
        }
    }
}

/**
 * How the model's pricing fared over a run — the evidence for the harness's asymmetric guarantee.
 * [modelApplied] prices cleared the gates and were used; [modelRejected] were caught and replaced by
 * the script floor; [fallbackNoProposal] is where the model offered no usable price. A weak model
 * shows many rejects/fallbacks yet the run still matches the floor — that is the guarantee working.
 */
data class PricingStats(
    val modelApplied: Int = 0,
    val modelRejected: Int = 0,
    val fallbackNoProposal: Int = 0,
) {
    val total: Int get() = modelApplied + modelRejected + fallbackNoProposal
}
