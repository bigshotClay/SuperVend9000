package dev.supervend.procure

import dev.supervend.model.ProductId
import dev.supervend.model.SupplierReplyClass
import dev.supervend.model.SupplierId
import kotlinx.serialization.Serializable

/**
 * The negotiation state machine (workplan §5, PRD R28–R30). It is a *pure, total* transition
 * function: outbound messages are template-instantiated by script, inbound replies are reduced to a
 * closed [SupplierReplyClass] verdict by the model, and every (state, verdict) pair has a defined
 * transition. Three properties are load-bearing and exhaustively tested (Stage 6 DoD):
 *
 *  - **Ambiguous never commits** (PRD R30): the escape valve routes to a clarify template or a
 *    walk-away, never to an approved quote — a forced-fit parse can never close a bad deal.
 *  - **Walk-away is reachable from every non-terminal state** (a refusal, a scam signal, or a cap
 *    breach always terminates the session cleanly).
 *  - **Round caps are enforced**: ≤2 counter rounds and ≤2 clarify rounds; any breach → walk away.
 *
 * The state machine holds no money logic; the QuoteSanity decision is computed by the harness gate
 * and handed in as [ReplyContext.sanityOk], so a commit that fails the sanity band walks away.
 */
object NegotiationFsm {

    const val COUNTER_CAP = 2
    const val CLARIFY_CAP = 2

    /** States that are waiting on a supplier reply and therefore act on a verdict. */
    private val AWAITING = setOf(NegState.RFQ_SENT, NegState.AWAIT_REPLY, NegState.COUNTER_ROUND, NegState.CLARIFY)

    /**
     * NEG-A: open a session by branching deterministically on source count (PRD R28). Single source →
     * one tight-band RFQ plus a standing task to find a second source; multi-source → parallel RFQ.
     * Zero sources → immediate walk-away.
     */
    fun open(session: NegotiationSession): Transition {
        check(session.state == NegState.NEED_SOURCE) { "open() requires NEED_SOURCE, was ${session.state}" }
        return when {
            session.supplierCount <= 0 -> walkAway(session, WalkAwayReason.NO_SOURCE)
            session.supplierCount == 1 -> Transition(
                session.copy(state = NegState.RFQ_SENT),
                NegOutput.SendRfq(parallel = false, tightBand = true, enqueueSingleSourceTask = true),
            )
            else -> Transition(
                session.copy(state = NegState.RFQ_SENT),
                NegOutput.SendRfq(parallel = true, tightBand = false, enqueueSingleSourceTask = false),
            )
        }
    }

    /**
     * NEG-C: react to a classified reply. Total over (state, verdict): a verdict arriving in a
     * non-awaiting state is a no-op ([NegOutput.Ignore]) rather than an error, keeping the table
     * complete without inventing illegal commits.
     */
    fun onReply(session: NegotiationSession, ctx: ReplyContext): Transition {
        if (session.state !in AWAITING) return Transition(session, NegOutput.Ignore)
        return when (ctx.verdict) {
            is SupplierReplyClass.Acceptance,
            is SupplierReplyClass.CounterWithinBand,
            -> if (ctx.sanityOk) commit(session, ctx) else walkAway(session, WalkAwayReason.SANITY_FAILED)

            is SupplierReplyClass.CounterAboveWalkAway ->
                if (session.counterRounds < COUNTER_CAP) {
                    Transition(
                        session.copy(state = NegState.COUNTER_ROUND, counterRounds = session.counterRounds + 1),
                        // NEG-D: the leverage template ("we have a quote at $X…") fires only with ≥2 live quotes.
                        NegOutput.SendCounter(leverage = session.liveQuotes >= 2),
                    )
                } else {
                    walkAway(session, WalkAwayReason.COUNTER_CAP)
                }

            is SupplierReplyClass.Refusal -> walkAway(session, WalkAwayReason.REFUSAL)

            is SupplierReplyClass.ScamSignal -> Transition(
                session.copy(state = NegState.WALK_AWAY),
                NegOutput.FlagAndWalkAway(
                    WalkAwayRecord(session.supplier, session.product, WalkAwayReason.SCAM),
                    flagTaskKind = "SupplierFlagTask",
                ),
            )

            is SupplierReplyClass.Ambiguous ->
                if (session.clarifyRounds < CLARIFY_CAP) {
                    Transition(
                        session.copy(state = NegState.CLARIFY, clarifyRounds = session.clarifyRounds + 1),
                        NegOutput.SendClarify,
                    )
                } else {
                    walkAway(session, WalkAwayReason.CLARIFY_CAP)
                }
        }
    }

    private fun commit(session: NegotiationSession, ctx: ReplyContext): Transition = Transition(
        session.copy(state = NegState.APPROVED),
        NegOutput.Commit(ApprovedQuote(session.supplier, session.product, ctx.unitCostCents, ctx.units)),
    )

    private fun walkAway(session: NegotiationSession, reason: WalkAwayReason): Transition = Transition(
        session.copy(state = NegState.WALK_AWAY),
        NegOutput.WalkAway(WalkAwayRecord(session.supplier, session.product, reason)),
    )
}

/** Negotiation states (workplan §5). APPROVED and WALK_AWAY are absorbing terminals. */
@Serializable
enum class NegState { NEED_SOURCE, RFQ_SENT, AWAIT_REPLY, COUNTER_ROUND, CLARIFY, APPROVED, WALK_AWAY }

/** One live negotiation, one product/supplier. Counters carry the round caps; pure value. */
@Serializable
data class NegotiationSession(
    val supplier: SupplierId,
    val product: ProductId,
    val supplierCount: Int,
    val state: NegState,
    val counterRounds: Int = 0,
    val clarifyRounds: Int = 0,
    /** Number of live parallel quotes in hand — gates the leverage template (NEG-D, ≥2). */
    val liveQuotes: Int = 0,
)

/** A classified reply plus the harness-resolved quote figures and the QuoteSanity gate outcome. */
data class ReplyContext(
    val verdict: SupplierReplyClass,
    val unitCostCents: Long = 0,
    val units: Int = 0,
    /** QuoteSanity gate decision (band + MAD), computed by the harness (PRD R23). */
    val sanityOk: Boolean = true,
)

/** The result of a transition: the next session and the one action the harness must carry out. */
data class Transition(val session: NegotiationSession, val output: NegOutput) {
    val committed: Boolean get() = output is NegOutput.Commit
    val walkedAway: Boolean get() = output is NegOutput.WalkAway || output is NegOutput.FlagAndWalkAway
}

/** The closed set of negotiation side effects. Outbound text is a template choice, not free text. */
@Serializable
sealed interface NegOutput {
    @Serializable data class SendRfq(val parallel: Boolean, val tightBand: Boolean, val enqueueSingleSourceTask: Boolean) : NegOutput
    @Serializable data class SendCounter(val leverage: Boolean) : NegOutput
    @Serializable data object SendClarify : NegOutput
    @Serializable data class Commit(val quote: ApprovedQuote) : NegOutput
    @Serializable data class WalkAway(val record: WalkAwayRecord) : NegOutput
    @Serializable data class FlagAndWalkAway(val record: WalkAwayRecord, val flagTaskKind: String) : NegOutput
    @Serializable data object Ignore : NegOutput
}

@Serializable
enum class WalkAwayReason { COUNTER_CAP, CLARIFY_CAP, REFUSAL, SCAM, SANITY_FAILED, NO_SOURCE }

/** Terminal artifact feeding PRO-03/BOOT-06 (workplan §5). Money stays in integer cents. */
@Serializable
data class ApprovedQuote(val supplier: SupplierId, val product: ProductId, val unitCostCents: Long, val units: Int)

/** Terminal artifact feeding PRO-05 re-sourcing (workplan §5). */
@Serializable
data class WalkAwayRecord(val supplier: SupplierId, val product: ProductId, val reason: WalkAwayReason)
