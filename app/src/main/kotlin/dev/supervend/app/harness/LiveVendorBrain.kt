package dev.supervend.app.harness

import dev.supervend.llm.JsonSchema
import dev.supervend.llm.ModelPort
import dev.supervend.model.Cents
import dev.supervend.sim.SupplierEngine
import dev.supervend.sim.VendorBrain
import dev.supervend.sim.VendorReplyRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

/**
 * The live (LLM) supplier engine (LIVE-SUPPLIERS-SPEC §3-4). Implements the sim's pure [VendorBrain]
 * seam from the `app` layer, where model access lives: it assembles a vendor [dev.supervend.llm.ContextPackage]
 * (state brief → inputs → creed → generated schema, in that fixed order) via [ModelSeat], calls the
 * *vendor* model, and maps the typed reply to the existing [SupplierEngine.Reply]. Everything
 * downstream — render, inbox, the buyer's classifier, the negotiation FSM, and every money gate — is
 * unchanged: a live vendor is indistinguishable from a scripted one past this boundary.
 *
 * Resilience (the harness's defining move): if the vendor model is unusable or emits junk, we fall
 * back to the deterministic [SupplierEngine.reply] against the seeded Rational persona rather than
 * wedge the run — the vendor model is never load-bearing.
 */
class LiveVendorBrain(
    model: ModelPort,
    modelId: String,
    private val buyerMarginFloorBps: Long,
) : VendorBrain {

    private val seat = ModelSeat(model, modelId)
    private val schema = JsonSchema.of<VendorReplyDoc>()

    override fun reply(req: VendorReplyRequest): SupplierEngine.Reply {
        val doc = runBlocking {
            seat.call(
                creed = VENDOR_CREED,
                stateBrief = stateBrief(req),
                inputs = "Buyer's message:\n${req.rfqBody}",
                schema = schema,
                serializer = VendorReplyDoc.serializer(),
            ).value
        } ?: return fallback(req)
        return map(doc, req)
    }

    /** The vendor's context each turn: its own economics, the buyer's dependence, the session so far. */
    private fun stateBrief(req: VendorReplyRequest): String {
        val cost = req.product.wholesaleCostCents.value
        val floor = SupplierEngine.floorPrice(req.product, req.persona.floorMult).value
        val depend = when {
            req.dependence >= 0.66 -> "HIGH (this buyer leans on you — you can push)"
            req.dependence >= 0.33 -> "MEDIUM"
            else -> "LOW (buyer has cheaper alternatives — compete to win the sale)"
        }
        val hist = if (req.history.isEmpty()) "none yet"
        else req.history.joinToString("; ") { (if (it.fromBuyer) "buyer: " else "you: ") + it.text }
        return buildString {
            appendLine("You are ${req.supplierName}, a wholesale vendor in a competitive (buyer's) market.")
            appendLine("Product: ${req.product.id.value}. Your unit cost: ${cost}c. Your hard floor: ${floor}c (never quote below it).")
            appendLine("Your target margin ambition: ${(req.persona.targetMargin * 100).toInt()}% of the buyer's headroom.")
            appendLine("Buyer dependence on you: $depend.")
            appendLine("Negotiation round: ${req.round} (0 = opening).")
            append("Session so far: $hist")
        }
    }

    /** Map the model's intent to a typed reply, enforcing the floor (the sim re-clamps as a backstop). */
    private fun map(doc: VendorReplyDoc, req: VendorReplyRequest): SupplierEngine.Reply {
        val floor = SupplierEngine.floorPrice(req.product, req.persona.floorMult).value
        val price = Cents(doc.unitCents.toLong().coerceAtLeast(floor))
        return when (doc.intent) {
            VendorIntent.QUOTE -> SupplierEngine.Reply.Quote(price)
            VendorIntent.COUNTER -> if (req.round == 0) SupplierEngine.Reply.Quote(price) else SupplierEngine.Reply.Counter(price)
            VendorIntent.ACCEPT -> if (req.round == 0) SupplierEngine.Reply.Quote(price) else SupplierEngine.Reply.Counter(price)
            VendorIntent.DECLINE -> SupplierEngine.Reply.Refusal
        }
    }

    // Deterministic fallback: play the seeded Rational persona through the scripted engine.
    private fun fallback(req: VendorReplyRequest): SupplierEngine.Reply {
        val profile = dev.supervend.sim.SupplierProfile.Rational(
            floorMult = req.persona.floorMult, greed = req.persona.targetMargin, deliveryCut = 0.0,
        )
        return SupplierEngine.reply(profile, req.product, req.round, req.day, req.dependence, buyerMarginFloorBps)
    }

    companion object {
        /**
         * Vendor creed (≤150 tokens, sterile ROLE/TASK/RULES/OUTPUT — CLAUDE.md #8). A rational,
         * profit-seeking, good-faith vendor: anchors high, concedes toward its floor under pressure,
         * and always prefers a completed sale to shipping nothing.
         */
        const val VENDOR_CREED = """ROLE: A rational, profit-seeking wholesale vendor in a competitive market.
TASK: Reply to the buyer's message with one negotiation move.
RULES:
- Never quote below your hard floor. A completed sale at thin margin beats no sale.
- Open near your target; each round the buyer counters, concede a fraction toward your floor.
- It is a buyer's market: the buyer can walk to a competitor, so compete to close the deal.
- Push harder only when the buyer's dependence on you is HIGH.
- Reveal your best price late and only under pressure. Stay terse and businesslike.
OUTPUT: One JSON object matching the schema. intent is QUOTE (opening), COUNTER (concede), ACCEPT (agree to buyer's price), or DECLINE (only if the buyer is below your floor). unitCents is your price in cents; message is one sentence to the buyer."""
    }
}

/** The model's structured vendor reply (schema generated from this type — CLAUDE.md #7). */
@Serializable
data class VendorReplyDoc(
    val intent: VendorIntent,
    val unitCents: Int,
    val message: String,
)

@Serializable
enum class VendorIntent { QUOTE, COUNTER, ACCEPT, DECLINE }
