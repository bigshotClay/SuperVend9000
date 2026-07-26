package dev.supervend.app.harness

import dev.supervend.gates.GateConfig
import dev.supervend.gates.GateRegistry
import dev.supervend.llm.JsonSchema
import dev.supervend.llm.ModelPort
import dev.supervend.model.BasisPoints
import dev.supervend.model.Cents
import dev.supervend.model.Location
import dev.supervend.model.ProductId
import dev.supervend.model.ProposedAction
import dev.supervend.orchestrator.GateLogEntry
import dev.supervend.orchestrator.StateActor
import dev.supervend.plan.Workplan
import dev.supervend.sim.DayReport
import dev.supervend.sim.Move
import dev.supervend.sim.ScriptedEnvironment
import dev.supervend.sim.SimConfig
import dev.supervend.state.LedgerSource
import dev.supervend.state.LedgerStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.Connection

/**
 * The full integration (OPERATOR-INPUT #19 closed): the real model works every model-assigned task,
 * against the live adversarial sim, with every money action gated. Script fundamentals are the floor;
 * the model adds judgment on top and the gates make it one-directional against catastrophe.
 *
 * Model seats exercised each run: **pricing** (consequential — the profit lever), **procurement
 * reply-classification** (consequential — the scam defense), **scribe** (the daily narrative), and
 * **weekly auditor** (amendment proposals). Suppliers include the prepay scammer, bait-and-switch,
 * slow-shipper, and the vendor that goes dark on day 20 — so procurement is a real gauntlet.
 */
class FullRunner(
    private val model: ModelPort,
    private val modelId: String,
    private val config: SimConfig = SimConfig.default(),
    private val gateConfig: GateConfig = GateConfig.default(),
    /** MODEL: the model works every seat. SCRIPT: pure-fundamentals ablation, no LLM in the loop. */
    private val decisions: Decisions = Decisions.MODEL,
    /**
     * Live-vendor brain (LIVE-SUPPLIERS-SPEC). Non-null only when the config is [dev.supervend.sim.SupplierMode.LIVE]
     * and a vendor model is wired; otherwise the sim uses its deterministic scripted fallback.
     */
    private val vendorBrain: dev.supervend.sim.VendorBrain? = null,
    /**
     * R1a ablation seam. MODEL default enforces the gate suite. When false, gates are swapped for a
     * pass-through (same ids, always Allow) — the run keeps the full call structure, budget, seats,
     * and externalized state, but no gate ever binds. Isolates "gates" from the rest of the harness.
     */
    private val enforceGates: Boolean = true,
    /**
     * R1b ablation seam (OPERATOR-INPUT #23). MODEL default hands the model ground-truth state each
     * day (pricing Observation + procurement `need` from `world.onHand`). When false, the model
     * maintains its own running belief and prices/reorders from it — the daily scribe call is
     * repurposed as the state-maintainer, so the call budget is unchanged. Gates stay ON.
     */
    private val externalizeState: Boolean = true,
    /**
     * R5 seat ablation (which model seat carries the lift). When true, the pricing seat is replaced
     * by the deterministic script price (margin-safe reference), while procurement-classify, scribe,
     * and weekly-audit remain model-driven and gates/state stay on. Isolates the pricing seat's
     * contribution. Ignored when [decisions] is SCRIPT (that already scripts every seat).
     */
    private val scriptPricing: Boolean = false,
) {
    enum class Decisions { MODEL, SCRIPT }
    private val scripted get() = decisions == Decisions.SCRIPT
    private val stateMaintainer = StateMaintainer(ModelSeat(model, modelId))
    // Target ~60% margin; the reservation price above which we hold out against a vendor squeeze.
    private fun reservation(p: dev.supervend.sim.ProductConfig): Long = p.referencePriceCents.value * 10_000 / 16_000
    private val json = Json { encodeDefaults = true }
    private val gates = if (enforceGates) GateRegistry(gateConfig) else GateRegistry.passthrough(gateConfig)
    private val priceStep = Workplan.PLAN.allSteps.first { it.id.value == "PRC-02" }
    private val orderStep = Workplan.PLAN.allSteps.first { it.id.value == "PRO-03" }
    private val seat = ModelSeat(model, modelId)
    private val pricingSeat = PricingSeat(model, modelId)

    var stats: FullStats = FullStats(); private set

    // Provenance label for the run_manifest so each ablation arm is attributable from its DB alone.
    // Full and scripts arms keep the original "harness" label (byte-identical manifest); each
    // ablation gets a distinct kind.
    private val runKind: String = when {
        !enforceGates -> "harness-R1a-nogates"
        !externalizeState -> "harness-R1b-nostate"
        scriptPricing -> "harness-R5-scriptpricing"
        else -> "harness"
    }

    suspend fun run(runId: String, seed: Long, days: Int, conn: Connection): List<DayReport> {
        val env = ScriptedEnvironment(config, seed, vendorBrain)
        val ledger = LedgerStore(conn)
        val world = SimWorld(env, config)
        val procure = Procurement(env, config, seat, gates, orderStep)
        // A slow shipper delivers within default-lag + a few days; only after that is a shortfall real.
        val rel = VendorRelationships(maturityDays = config.defaultDeliveryLagDays + 8)
        // Established vendor per product: once discovered (RFQ + model classify + gates), reorders go
        // straight to it as a gated script action — no re-classifying the whole market every restock.
        val preferred = mutableMapOf<ProductId, Pair<dev.supervend.model.SupplierId, Cents>>()
        val reports = mutableListOf<DayReport>()
        writeManifest(conn, runId, seed)
        var lastReport: DayReport? = null
        var priorReport: DayReport? = null
        // R1b only: the model's self-maintained belief, carried day to day, plus yesterday's orders.
        var belief = R1bBelief.seed(config)
        var ordersPlacedYesterday = emptyList<String>()

        for (day in 1..days) {
            world.startDay()
            val gateLog = mutableListOf<GateLogEntry>()
            val actor = StateActor(gates, world, gateLog)

            env.collectMachineCash()

            // R1b STATE MAINTENANCE — repurposes the daily scribe call (no budget change): the model
            // folds yesterday's raw events into its own records; pricing/procurement then read those.
            if (!scripted && !externalizeState) {
                val events = stateMaintainer.eventsInputs(
                    sold = lastReport?.unitsSold ?: emptyMap(),
                    deliveries = lastReport?.deliveries?.map { it.product to it.units } ?: emptyList(),
                    ordered = ordersPlacedYesterday,
                    cashCents = lastReport?.revenue?.value ?: 0,
                )
                val r = stateMaintainer.update(day, belief, events)
                recordLlmCall(conn, day, "state-maintain", toUsage(r.completion))
                if (r.usable) belief = r.value!! // unusable → keep prior belief (no ground-truth fallback)
            }
            val ordersPlacedToday = mutableListOf<String>()

            // PRICING — the model's profit lever, gated with a script fallback.
            val modelPrices = priceDecisions(day, env, world, actor, conn, lastReport, priorReport, belief)

            // PROCUREMENT — RFQ the roster the reliability record still trusts; model classifies; gates
            // guard the money; vendors that under-deliver are dropped from future rounds.
            for (p in config.products) {
                // Target a machine-full buffer; skip if on-hand + already-inbound stock covers it, so we
                // don't re-RFQ the roster daily while an order is in transit (keeps calls under budget).
                val target = p.machineCapacity * 2
                // R1b: reorder from the model's *believed* stock/in-transit, not ground truth.
                val onHandView = if (externalizeState) world.onHand(p.id) else belief.onHand(p.id)
                val pendingView = if (externalizeState) rel.pendingUnits(p.id, day) else belief.inTransit(p.id)
                val need = target - onHandView - pendingView
                if (need <= 0) continue

                val pref = preferred[p.id]
                if (pref != null && rel.trusted(pref.first, day)) {
                    // TRUSTED vendor → trust-but-verify: fast-track reorder, no model call. Delivery is
                    // still recorded and the next bad trade demotes it (tit-for-tat).
                    actor.submit(orderStep, listOf(ProposedAction.PlaceOrder(pref.first, p.id, need, pref.second)))
                    stats = stats.copy(ordersPlaced = stats.ordersPlaced + 1)
                    ordersPlacedToday += "${p.id.value} x$need from ${pref.first.value}"
                } else {
                    // Verify-then-trust: full discovery (RFQ + model classify + gate), excluding DROPPED
                    // vendors. Re-runs whenever the incumbent isn't trusted — new, on probation, or demoted.
                    preferred.remove(p.id)
                    val urgent = onHandView + pendingView < p.machineCapacity / 2
                    val outcome = procure.procure(
                        p, need, world, actor,
                        isAcceptable = { !rel.dropped(it, day) },
                        onCall = { c -> recordLlmCall(conn, day, "procure-classify", toUsage(c)); stats = stats.copy(classificationCalls = stats.classificationCalls + 1) },
                        // Least-bad by expected margin per unit = reliability × (achievable retail − cost).
                        // Achievable retail is what we can actually charge (price the cost THROUGH at the
                        // margin floor), not the reference — so a squeezed-but-viable vendor still reads
                        // profitable and we keep buying (at higher price/lower volume) instead of going dark.
                        valueOf = { s, cost ->
                            val achievableRetail = maxOf(p.referencePriceCents.value, cost * (10_000 + gateConfig.marginFloorBps.value) / 10_000)
                            rel.reliabilityEstimate(s) * (achievableRetail - cost).coerceAtLeast(0).toDouble()
                        },
                        reservationCost = reservation(p),
                        urgent = urgent,
                        scriptClassify = if (scripted) ({ e -> Procurement.heuristicClassify(e) }) else null,
                    )
                    if (outcome.ordered && outcome.supplier != null) {
                        preferred[p.id] = outcome.supplier!! to outcome.unitCost
                        ordersPlacedToday += "${p.id.value} x$need from ${outcome.supplier!!.value}"
                    }
                    stats = stats.fold(outcome)
                }
            }
            // Register the day's placed orders so trust can be reconciled on delivery.
            world.takeNewOrders().forEach { rel.recordOrder(it) }

            // Restock the machine from whatever actually arrived (scam/oob orders simply never show up).
            val moves = config.products.mapNotNull { p ->
                val toMove = minOf(world.storageOf(p.id), p.machineCapacity - world.machineOf(p.id))
                if (toMove > 0) Move(p.id, toMove) else null
            }
            if (moves.isNotEmpty()) env.restock(moves)

            // SCRIBE + weekly audit are model tasks — skipped in the scripts-only ablation. In R1b the
            // daily scribe is replaced by the state-maintain call above (same budget), so skip it here.
            if (!scripted) {
                if (externalizeState) scribe(day, conn, lastReport)
                if (day % 7 == 0) weeklyAudit(day, conn)
            }
            ordersPlacedYesterday = ordersPlacedToday

            val report = env.advanceDay()
            // Reconcile: credit each delivery to its order so shortfalls/non-delivery lower the vendor's score.
            report.deliveries.forEach { rel.recordDelivery(it.fromOrder, it.units) }
            stats = stats.copy(unitsDelivered = stats.unitsDelivered + report.deliveries.sumOf { it.units })
            mirrorLedger(ledger, report)
            persistDay(conn, runId, report)
            persistGateLog(conn, gateLog)
            reports += report
            priorReport = lastReport
            lastReport = report
            // Heartbeat for long horizons (10k-day runs are hours) — observable via the run log.
            if (day % 250 == 0) {
                System.err.println("[progress] day=$day net_worth=${report.netWorthAfter.value}c orders=${stats.ordersPlaced} classify=${stats.classificationCalls} scam-orders=${stats.scamOrdersPlaced}")
            }
            if (report.terminated) break
        }
        // Final trust verdict per vendor — shows the tit-for-tat machine's judgment (adversaries DROPPED).
        System.err.println("[vendors] " + config.suppliers.joinToString("  ") { "${it.id.value}=${rel.state(it.id, env.currentDay())}" })
        return reports
    }

    private suspend fun priceDecisions(day: Int, env: ScriptedEnvironment, world: SimWorld, actor: StateActor, conn: Connection, last: DayReport?, prior: DayReport?, belief: R1bBelief): Unit {
        // Script pricing: the scripts-only ablation (all seats) OR the R5 pricing-seat ablation
        // (pricing scripted, other seats still model) — either way, no model pricing call today.
        if (scripted || scriptPricing) {
            for (p in config.products) {
                actor.submit(priceStep, listOf(ProposedAction.SetPrice(p.id, targetPrice(p))))
                stats = stats.copy(priceFallback = stats.priceFallback + 1)
            }
            return
        }
        // R1b: price from the model's self-maintained belief; full: from the ground-truth observation.
        val proposal = if (!externalizeState) {
            pricingSeat.proposeFromBelief(day, belief, config)
        } else {
            val obs = config.products.map { p ->
                PricingSeat.Observation(
                    product = p,
                    currentPrice = env.machineInventory().firstOrNull { it.product == p.id }?.price ?: Cents.ZERO,
                    soldYesterday = last?.unitsSold?.get(p.id) ?: 0,
                    soldPrior = prior?.unitsSold?.get(p.id) ?: 0,
                    machineUnits = world.machineOf(p.id),
                    unitCost = world.costBasis(p.id),
                )
            }
            pricingSeat.propose(day, obs)
        }
        recordLlmCall(conn, day, "pricing", proposal.usage.let { toUsage(it) })
        for (p in config.products) {
            val scriptPrice = targetPrice(p)
            val modelPrice = proposal.prices[p.id]
            if (modelPrice == null) {
                actor.submit(priceStep, listOf(ProposedAction.SetPrice(p.id, scriptPrice)))
                stats = stats.copy(priceFallback = stats.priceFallback + 1)
            } else {
                val submit = actor.submit(priceStep, listOf(ProposedAction.SetPrice(p.id, modelPrice)))
                if (submit.reject != null) {
                    actor.submit(priceStep, listOf(ProposedAction.SetPrice(p.id, scriptPrice)))
                    stats = stats.copy(priceRejected = stats.priceRejected + 1)
                } else {
                    stats = stats.copy(priceApplied = stats.priceApplied + 1)
                }
            }
        }
    }

    private suspend fun scribe(day: Int, conn: Connection, last: DayReport?) {
        val brief = "Day $day. Revenue yesterday ${last?.revenue?.value ?: 0}c, bank ${last?.bankBalanceAfter?.value ?: 0}c."
        val r = seat.call(SCRIBE_CREED, brief, "none", SUMMARY_SCHEMA, DailySummaryDoc.serializer())
        recordLlmCall(conn, day, "scribe", toUsage(r.completion))
    }

    private suspend fun weeklyAudit(day: Int, conn: Connection) {
        val brief = "Week ending day $day. Propose 0-3 bounded amendments (price band ±2000bps) or none."
        val r = seat.call(AUDIT_CREED, brief, "none", AMEND_SCHEMA, AmendmentDoc.serializer())
        recordLlmCall(conn, day, "weekly-audit", toUsage(r.completion))
        if (r.usable) stats = stats.copy(amendmentsProposed = stats.amendmentsProposed + (r.value?.amendments?.size ?: 0))
    }

    private fun targetPrice(p: dev.supervend.sim.ProductConfig): Cents {
        val floor = p.wholesaleCostCents.scaleBps(BasisPoints(BasisPoints.ONE_HUNDRED_PERCENT + gateConfig.marginFloorBps.value))
        return if (p.referencePriceCents >= floor) p.referencePriceCents else floor
    }

    private fun mirrorLedger(ledger: LedgerStore, report: DayReport) {
        if (report.revenue.value != 0L) ledger.appendCash(report.day, report.revenue, LedgerSource.SIM_TOOL, "sales day ${report.day}")
        if (report.feeCharged.value != 0L) ledger.appendCash(report.day, Cents(-report.feeCharged.value), LedgerSource.SIM_TOOL, "fee day ${report.day}")
        for (d in report.deliveries) ledger.appendInventory(report.day, d.product, Location.STORAGE, d.units, LedgerSource.SIM_TOOL, "delivery ${d.fromOrder}")
    }

    // ---- persistence (shared shape with HarnessRunner) ----
    private data class U(val tin: Int, val tout: Int, val latency: Long, val status: Int)
    private fun toUsage(c: dev.supervend.llm.CompletionResult): U = when (c) {
        is dev.supervend.llm.CompletionResult.Completed -> U(c.usage.tokensIn, c.usage.tokensOut, c.usage.latencyMs, c.usage.httpStatus)
        is dev.supervend.llm.CompletionResult.Unusable -> U(0, 0, 0, 0)
    }

    private fun writeManifest(conn: Connection, runId: String, seed: Long) {
        conn.prepareStatement("INSERT INTO run_manifest(run_id, model_slots_json, plan_hash, gate_config_sha, sim_seed, git_commit, run_kind, model_id, sim_config_sha) VALUES (?,?,?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, runId); ps.setString(2, "[]"); ps.setString(3, Workplan.PLAN.version().hash.hex); ps.setString(4, gateConfig.hash().hex)
            ps.setLong(5, seed); ps.setString(6, ""); ps.setString(7, runKind); ps.setString(8, modelId); ps.setString(9, config.hash().hex)
            ps.executeUpdate()
        }
    }

    private fun persistDay(conn: Connection, runId: String, report: DayReport) {
        conn.prepareStatement("INSERT INTO day_report(run_id, day, report_json) VALUES (?,?,?)").use { ps ->
            ps.setString(1, runId); ps.setInt(2, report.day); ps.setString(3, json.encodeToString(DayReport.serializer(), report)); ps.executeUpdate()
        }
    }

    private fun persistGateLog(conn: Connection, entries: List<GateLogEntry>) {
        for (e in entries) conn.prepareStatement("INSERT INTO gate_log(day, step_id, gate_id, decision_json, payload_sha) VALUES (?,?,?,?,?)").use { ps ->
            ps.setInt(1, e.day); ps.setString(2, e.stepId.value); ps.setString(3, e.gateId)
            val decision = kotlinx.serialization.json.buildJsonObject {
                put("allowed", kotlinx.serialization.json.JsonPrimitive(e.allowed))
                put("reason", kotlinx.serialization.json.JsonPrimitive(e.reason))
            }
            ps.setString(4, decision.toString())
            ps.setString(5, dev.supervend.model.Hashing.sha256("${e.stepId.value}:${e.actionType}").hex); ps.executeUpdate()
        }
    }

    private fun recordLlmCall(conn: Connection, day: Int, step: String, u: U) {
        conn.prepareStatement("INSERT INTO llm_calls(day, step_id, model_id, prompt_sha, tokens_in, tokens_out, latency_ms, http_status) VALUES (?,?,?,?,?,?,?,?)").use { ps ->
            ps.setInt(1, day); ps.setString(2, step); ps.setString(3, modelId); ps.setString(4, "")
            ps.setInt(5, u.tin); ps.setInt(6, u.tout); ps.setLong(7, u.latency); ps.setInt(8, u.status); ps.executeUpdate()
        }
    }

    private companion object {
        val SUMMARY_SCHEMA = JsonSchema.of<DailySummaryDoc>()
        val AMEND_SCHEMA = JsonSchema.of<AmendmentDoc>()
        const val SCRIBE_CREED = "ROLE: scribe for a vending business.\nTASK: write today's summary.\nRULES:\n1. Summarize only the supplied numbers.\n2. Narrative ≤ 40 words.\n3. Invent no figures.\nOUTPUT: JSON only, matching the schema."
        const val AUDIT_CREED = "ROLE: weekly auditor.\nTASK: propose bounded plan amendments.\nRULES:\n1. Only price-band nudges within ±2000 bps.\n2. Empty list is valid.\n3. Never restructure the plan.\nOUTPUT: JSON only, matching the schema."
    }
}

/** Instrumentation across a full run — the evidence for the multi-axis harness. */
data class FullStats(
    val priceApplied: Int = 0,
    val priceRejected: Int = 0,
    val priceFallback: Int = 0,
    val classificationCalls: Int = 0,
    val scamsFlaggedByModel: Int = 0,
    val ordersPlaced: Int = 0,
    val ordersGateBlocked: Int = 0,
    val scamOrdersPlaced: Int = 0,
    val unreliableDropped: Int = 0,
    val unitsDelivered: Int = 0,
    val amendmentsProposed: Int = 0,
) {
    fun fold(o: Procurement.Outcome): FullStats = copy(
        scamsFlaggedByModel = scamsFlaggedByModel + o.scamFlaggedByModel,
        ordersPlaced = ordersPlaced + if (o.ordered) 1 else 0,
        ordersGateBlocked = ordersGateBlocked + if (o.gateBlocked != null) 1 else 0,
        scamOrdersPlaced = scamOrdersPlaced + if (o.scamOrderPlaced) 1 else 0,
        unreliableDropped = maxOf(unreliableDropped, o.unreliableDropped),
    )
}

@Serializable
private data class DailySummaryDoc(val narrative: String = "", val revenueCents: Long = 0)

@Serializable
private data class AmendmentDoc(val amendments: List<String> = emptyList())
