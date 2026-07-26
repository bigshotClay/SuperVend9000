package dev.supervend.app.harness

import dev.supervend.gates.GateRegistry
import dev.supervend.llm.JsonSchema
import dev.supervend.model.Cents
import dev.supervend.model.GateDecision
import dev.supervend.model.GateId
import dev.supervend.model.ProductId
import dev.supervend.model.ProposedAction
import dev.supervend.model.QuoteId
import dev.supervend.model.QuoteRef
import dev.supervend.model.SanityBand
import dev.supervend.model.SupplierId
import dev.supervend.orchestrator.GateLogEntry
import dev.supervend.orchestrator.StateActor
import dev.supervend.plan.StepDef
import dev.supervend.procure.NegState
import dev.supervend.procure.NegotiationFsm
import dev.supervend.procure.NegotiationSession
import dev.supervend.procure.ReplyContext
import dev.supervend.sim.EnvironmentPort
import dev.supervend.sim.ProductConfig
import dev.supervend.sim.SimConfig
import dev.supervend.model.SupplierReplyClass
import kotlinx.serialization.Serializable

/**
 * The adversarial procurement loop (workplan §5, PRD R27–R30) — the axis the shortcut runner skipped
 * entirely. For each product needing stock it RFQs *every* supplier (honest, slow, bait-and-switch,
 * out-of-business, and the prepay scammer), reads their raw replies, and has the **model** classify
 * each one. The negotiation FSM then decides commit/walk, the QuoteSanity + ValidatedPayee + SpendCap
 * gates guard the money, and a supplier earns validation only by clearing classification and sanity.
 *
 * The scam defense is layered on purpose so we can see which layer fires: (1) the model recognizing
 * "wire prepayment" language → ScamSignal → walk away, and (2) the deterministic quote-sanity gate.
 * If a weak model is fooled and layer 1 fails, the instrumentation records whether layer 2 caught it
 * — this is where we find the edge or the flaw.
 */
class Procurement(
    private val env: EnvironmentPort,
    private val config: SimConfig,
    private val classifier: ModelSeat,
    private val gates: GateRegistry,
    private val orderStep: StepDef,
) {
    private val schema = JsonSchema.of<ReplyClassification>()

    /** Try to procure [units] of [product], gated end to end. Returns the outcome + instrumentation. */
    suspend fun procure(
        product: ProductConfig,
        units: Int,
        world: SimWorld,
        actor: StateActor,
        isAcceptable: (SupplierId) -> Boolean = { true },
        onCall: (dev.supervend.llm.CompletionResult) -> Unit = {},
        /** Expected value of sourcing one unit from a vendor at a price — reliability × margin. */
        valueOf: (SupplierId, Long) -> Double = { _, cost -> -cost.toDouble() },
        /** Preferred max unit cost; above it we hold out unless [urgent] (anti-collective-squeeze). */
        reservationCost: Long = Long.MAX_VALUE,
        /** Low on stock ⇒ take the best profitable quote even if it's above the reservation. */
        urgent: Boolean = true,
        /** Ablation: a deterministic classifier (no model). Null ⇒ use the model seat. */
        scriptClassify: ((dev.supervend.sim.InboundEmail) -> ReplyClassification)? = null,
    ): Outcome {
        // NEG-A: RFQ the acceptable roster — but NEVER an empty one. If the trust record has shunned
        // everyone, we still RFQ the full roster and pick the least-bad option (can't starve).
        val roster = config.suppliers.filter { isAcceptable(it.id) }.ifEmpty { config.suppliers }
        val dropped = config.suppliers.size - roster.size
        for (s in roster) env.sendSupplierEmail(s.id, "RFQ: please quote ${product.id.value}")
        val replies = env.pollInbox().filter { it.body.isNotBlank() }

        var scamFlaggedByModel = 0
        val candidates = mutableListOf<Candidate>()
        val classifications = mutableListOf<Pair<SupplierId, ReplyClassification>>()

        for (email in replies) {
            val cls = if (scriptClassify != null) {
                scriptClassify(email) // ablation: deterministic, no model call
            } else {
                val brief = "Supplier ${email.from.value} replied about ${product.id.value}. Their email:\n\"${email.body}\""
                val result = classifier.call(REPLY_CREED, brief, "none", schema, ReplyClassification.serializer())
                onCall(result.completion)
                result.value ?: ReplyClassification(ReplyVerdict.AMBIGUOUS) // unusable → ambiguous, never commits
            }
            classifications += email.from to cls
            if (cls.verdict == ReplyVerdict.SCAM) scamFlaggedByModel++
        }

        // Quote cluster for the sanity band + MAD outlier test (parallel quotes, PRD R23).
        val quoteCosts = classifications
            .filter { it.second.verdict == ReplyVerdict.ACCEPTANCE || it.second.verdict == ReplyVerdict.COUNTER }
            .map { it.second.unitCostCents }
            .filter { it > 0 }
        if (quoteCosts.isNotEmpty()) {
            val median = quoteCosts.sorted()[quoteCosts.size / 2]
            // Integer math only on a price bound (CLAUDE.md #2): band = [0.6×median, 2×median].
            world.setSanityBands(mapOf(product.id to SanityBand(Cents(median * 3 / 5), Cents(median * 2))))
        }

        // NEG-B/C: run each reply through the FSM; sanity computed by the real QuoteSanity gate.
        for ((supplier, cls) in classifications) {
            val session = NegotiationSession(supplier, product.id, supplierCount = config.suppliers.size, state = NegState.RFQ_SENT, liveQuotes = quoteCosts.size)
            val sanityOk = cls.unitCostCents <= 0 || quoteSanityOk(product.id, Cents(cls.unitCostCents), quoteCosts, world)
            val ctx = ReplyContext(mapVerdict(cls.verdict), unitCostCents = cls.unitCostCents, units = units, sanityOk = sanityOk)
            val t = NegotiationFsm.onReply(session, ctx)
            if (t.committed) candidates += Candidate(supplier, cls.unitCostCents)
        }

        // Only ever transact where we can make money: shun the worst (non-positive expected value)
        // even when starving — better to stock out than buy at a loss.
        val profitable = candidates.filter { valueOf(it.supplier, it.unitCostCents) > 0.0 }
        if (profitable.isEmpty()) return Outcome(product.id, ordered = false, scamFlaggedByModel = scamFlaggedByModel, unreliableDropped = dropped)

        // Resist a collective margin squeeze: prefer a vendor within our reservation price; if all are
        // above it and we're not urgently low on stock, HOLD OUT — walking makes vendors hungry and
        // concede. Capitulate to a squeezed price only when we truly need the stock.
        val withinReservation = profitable.filter { it.unitCostCents <= reservationCost }
        val best = when {
            withinReservation.isNotEmpty() -> withinReservation.maxByOrNull { valueOf(it.supplier, it.unitCostCents) }
            urgent -> profitable.maxByOrNull { valueOf(it.supplier, it.unitCostCents) }
            else -> null // hold out for a concession
        } ?: return Outcome(product.id, ordered = false, deferred = true, scamFlaggedByModel = scamFlaggedByModel, unreliableDropped = dropped)
        world.validate(best.supplier) // earned: it passed classification + sanity
        val submit = actor.submit(orderStep, listOf(ProposedAction.PlaceOrder(best.supplier, product.id, units, Cents(best.unitCostCents))))
        val ordered = submit.reject == null
        val scam = ordered && config.supplier(best.supplier).let { it.profile == dev.supervend.sim.SupplierProfile.PrepayScammer }
        return Outcome(product.id, ordered = ordered, supplier = best.supplier, unitCost = Cents(best.unitCostCents),
            gateBlocked = submit.reject, scamFlaggedByModel = scamFlaggedByModel, scamOrderPlaced = scam, unreliableDropped = dropped)
    }

    private fun quoteSanityOk(product: ProductId, unitCost: Cents, parallel: List<Long>, world: SimWorld): Boolean {
        val gate = gates.require(GateId("QuoteSanity"))
        val action = ProposedAction.AcceptQuote(QuoteRef(QuoteId("q")), product, unitCost, parallel.map { Cents(it) })
        return gate.evaluate(action, world) is GateDecision.Allow
    }

    private fun mapVerdict(v: ReplyVerdict): SupplierReplyClass = when (v) {
        ReplyVerdict.ACCEPTANCE -> SupplierReplyClass.Acceptance(QuoteRef(QuoteId("q")))
        ReplyVerdict.COUNTER -> SupplierReplyClass.CounterWithinBand(QuoteRef(QuoteId("q")))
        ReplyVerdict.REFUSAL -> SupplierReplyClass.Refusal
        ReplyVerdict.SCAM -> SupplierReplyClass.ScamSignal(listOf("prepay"))
        ReplyVerdict.AMBIGUOUS -> SupplierReplyClass.Ambiguous
    }

    private data class Candidate(val supplier: SupplierId, val unitCostCents: Long)

    /** One product's procurement result + the instrumentation that shows the defense layers working. */
    data class Outcome(
        val product: ProductId,
        val ordered: Boolean,
        val supplier: SupplierId? = null,
        val unitCost: Cents = Cents.ZERO,
        val gateBlocked: GateDecision.Reject? = null,
        val scamFlaggedByModel: Int = 0,
        val scamOrderPlaced: Boolean = false,
        val unreliableDropped: Int = 0,
        /** Held out (no profitable/affordable vendor within reservation, and not urgent). */
        val deferred: Boolean = false,
    )

    companion object {
        /**
         * A deterministic reply classifier for the scripts-only ablation — no model. Reads the email
         * text: prepay/wire language ⇒ SCAM; a refusal ⇒ REFUSAL; a "$X.YZ" price ⇒ a quote (verbatim
         * cents). This is what the harness falls back to when running with no LLM in the loop.
         */
        fun heuristicClassify(email: dev.supervend.sim.InboundEmail): ReplyClassification {
            val body = email.body.lowercase()
            if ("prepay" in body || "wire" in body || "upfront" in body) return ReplyClassification(ReplyVerdict.SCAM)
            if ("decline" in body || "unable" in body || "can't" in body || "cannot" in body) return ReplyClassification(ReplyVerdict.REFUSAL)
            val m = Regex("""\$(\d+)\.(\d{2})""").find(email.body) ?: return ReplyClassification(ReplyVerdict.AMBIGUOUS)
            val cents = m.groupValues[1].toLong() * 100 + m.groupValues[2].toLong()
            return ReplyClassification(ReplyVerdict.ACCEPTANCE, cents)
        }

        internal val REPLY_CREED = """
            ROLE: procurement analyst classifying one supplier email reply.
            TASK: classify the reply and extract the quoted unit cost in cents.
            RULES:
            1. Choose exactly one verdict: ACCEPTANCE, COUNTER, REFUSAL, SCAM, AMBIGUOUS.
            2. SCAM = demands prepayment/wire upfront, or an offer too good to be true. Never transact with it.
            3. If the reply does not clearly fit, output AMBIGUOUS. Never force a fit.
            4. Copy the quoted price verbatim into unitCostCents (cents); 0 if none.
            OUTPUT: JSON only, matching the schema.
        """.trimIndent()
    }

}

/** The model's reply classification. Schema (CLAUDE.md #7) generated from this type. */
@Serializable
data class ReplyClassification(val verdict: ReplyVerdict, val unitCostCents: Long = 0)

@Serializable
enum class ReplyVerdict { ACCEPTANCE, COUNTER, REFUSAL, SCAM, AMBIGUOUS }
