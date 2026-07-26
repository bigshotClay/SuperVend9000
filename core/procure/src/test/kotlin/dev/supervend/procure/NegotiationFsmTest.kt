package dev.supervend.procure

import dev.supervend.model.ProductId
import dev.supervend.model.QuoteId
import dev.supervend.model.QuoteRef
import dev.supervend.model.SupplierId
import dev.supervend.model.SupplierReplyClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private val SUP = SupplierId("SUP-HC")
private val COLA = ProductId("COLA")
private val Q = QuoteRef(QuoteId("q1"))

private val ALL_VERDICTS: List<SupplierReplyClass> = listOf(
    SupplierReplyClass.Acceptance(Q),
    SupplierReplyClass.CounterWithinBand(Q),
    SupplierReplyClass.CounterAboveWalkAway(Q),
    SupplierReplyClass.Refusal,
    SupplierReplyClass.ScamSignal(listOf("prepay-only")),
    SupplierReplyClass.Ambiguous,
)

private fun session(state: NegState, counter: Int = 0, clarify: Int = 0, live: Int = 0, sources: Int = 2) =
    NegotiationSession(SUP, COLA, supplierCount = sources, state = state, counterRounds = counter, clarifyRounds = clarify, liveQuotes = live)

class NegotiationFsmTest : FunSpec({

    // ---- Exhaustive transition table: every (state, verdict) is defined ----
    test("every NegState × verdict yields a defined transition") {
        for (state in NegState.entries) {
            for (verdict in ALL_VERDICTS) {
                val t = NegotiationFsm.onReply(session(state), ReplyContext(verdict, unitCostCents = 70, units = 30))
                // Totality: a transition exists and the session's state is a valid enum value.
                (t.session.state in NegState.entries) shouldBe true
            }
        }
    }

    // ---- Ambiguous never commits (PRD R30) ----
    test("Ambiguous never produces a Commit, in any state or round count") {
        for (state in NegState.entries) {
            for (clarify in 0..CLARIFY_CAP_PLUS) {
                val t = NegotiationFsm.onReply(session(state, clarify = clarify), ReplyContext(SupplierReplyClass.Ambiguous))
                t.committed shouldBe false
                (t.output is NegOutput.Commit) shouldBe false
            }
        }
    }

    // ---- Walk-away reachable everywhere ----
    test("walk-away is reachable from every awaiting state via a refusal") {
        for (state in listOf(NegState.RFQ_SENT, NegState.AWAIT_REPLY, NegState.COUNTER_ROUND, NegState.CLARIFY)) {
            NegotiationFsm.onReply(session(state), ReplyContext(SupplierReplyClass.Refusal)).walkedAway shouldBe true
        }
    }

    test("NEED_SOURCE reaches walk-away when there are no sources") {
        NegotiationFsm.open(session(NegState.NEED_SOURCE, sources = 0)).walkedAway shouldBe true
    }

    // ---- Round caps enforced ----
    test("counter rounds cap at 2, then walk away") {
        val within = NegotiationFsm.onReply(session(NegState.AWAIT_REPLY, counter = 1), ReplyContext(SupplierReplyClass.CounterAboveWalkAway(Q)))
        within.session.state shouldBe NegState.COUNTER_ROUND
        within.session.counterRounds shouldBe 2
        val breach = NegotiationFsm.onReply(session(NegState.COUNTER_ROUND, counter = 2), ReplyContext(SupplierReplyClass.CounterAboveWalkAway(Q)))
        breach.walkedAway shouldBe true
        (breach.output as NegOutput.WalkAway).record.reason shouldBe WalkAwayReason.COUNTER_CAP
    }

    test("clarify rounds cap at 2, then walk away") {
        val within = NegotiationFsm.onReply(session(NegState.AWAIT_REPLY, clarify = 1), ReplyContext(SupplierReplyClass.Ambiguous))
        within.session.state shouldBe NegState.CLARIFY
        within.session.clarifyRounds shouldBe 2
        val breach = NegotiationFsm.onReply(session(NegState.CLARIFY, clarify = 2), ReplyContext(SupplierReplyClass.Ambiguous))
        breach.walkedAway shouldBe true
        (breach.output as NegOutput.WalkAway).record.reason shouldBe WalkAwayReason.CLARIFY_CAP
    }

    // ---- Commit path + QuoteSanity gating ----
    test("acceptance within sanity commits an ApprovedQuote with the resolved figures") {
        val t = NegotiationFsm.onReply(session(NegState.AWAIT_REPLY), ReplyContext(SupplierReplyClass.Acceptance(Q), unitCostCents = 70, units = 30, sanityOk = true))
        val commit = t.output.shouldBeInstanceOf<NegOutput.Commit>()
        commit.quote shouldBe ApprovedQuote(SUP, COLA, 70, 30)
        t.session.state shouldBe NegState.APPROVED
    }

    test("a QuoteSanity failure walks away instead of committing") {
        val t = NegotiationFsm.onReply(session(NegState.AWAIT_REPLY), ReplyContext(SupplierReplyClass.CounterWithinBand(Q), unitCostCents = 999, units = 30, sanityOk = false))
        t.committed shouldBe false
        (t.output as NegOutput.WalkAway).record.reason shouldBe WalkAwayReason.SANITY_FAILED
    }

    // ---- Scam handling ----
    test("a scam signal flags the supplier and walks away") {
        val t = NegotiationFsm.onReply(session(NegState.AWAIT_REPLY), ReplyContext(SupplierReplyClass.ScamSignal(listOf("prepay"))))
        val out = t.output.shouldBeInstanceOf<NegOutput.FlagAndWalkAway>()
        out.flagTaskKind shouldBe "SupplierFlagTask"
        out.record.reason shouldBe WalkAwayReason.SCAM
    }

    // ---- Leverage template gated on ≥2 live quotes (NEG-D) ----
    test("leverage counter fires only with ≥2 live quotes") {
        val one = NegotiationFsm.onReply(session(NegState.AWAIT_REPLY, live = 1), ReplyContext(SupplierReplyClass.CounterAboveWalkAway(Q)))
        (one.output as NegOutput.SendCounter).leverage shouldBe false
        val two = NegotiationFsm.onReply(session(NegState.AWAIT_REPLY, live = 2), ReplyContext(SupplierReplyClass.CounterAboveWalkAway(Q)))
        (two.output as NegOutput.SendCounter).leverage shouldBe true
    }

    // ---- NEG-A source-count branch ----
    test("single source sends a tight-band RFQ and enqueues a second-source task") {
        val t = NegotiationFsm.open(session(NegState.NEED_SOURCE, sources = 1))
        val rfq = t.output.shouldBeInstanceOf<NegOutput.SendRfq>()
        rfq.parallel shouldBe false
        rfq.tightBand shouldBe true
        rfq.enqueueSingleSourceTask shouldBe true
        t.session.state shouldBe NegState.RFQ_SENT
    }

    test("multi source sends a parallel RFQ") {
        val rfq = NegotiationFsm.open(session(NegState.NEED_SOURCE, sources = 3)).output.shouldBeInstanceOf<NegOutput.SendRfq>()
        rfq.parallel shouldBe true
        rfq.enqueueSingleSourceTask shouldBe false
    }

    // ---- Verdicts arriving before an RFQ are ignored, not mis-committed ----
    test("a verdict in NEED_SOURCE is a no-op") {
        NegotiationFsm.onReply(session(NegState.NEED_SOURCE), ReplyContext(SupplierReplyClass.Acceptance(Q))).output shouldBe NegOutput.Ignore
    }
}) {
    companion object {
        private const val CLARIFY_CAP_PLUS = 3
    }
}
