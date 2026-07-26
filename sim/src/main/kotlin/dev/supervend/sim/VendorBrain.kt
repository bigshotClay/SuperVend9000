package dev.supervend.sim

import dev.supervend.model.SupplierId

/**
 * The live-vendor seam (LIVE-SUPPLIERS-SPEC §3-5). `sim` stays llm-free — it depends only on
 * `core:model` — so the model call itself lives in the `app` layer. The sim reaches a live vendor
 * only through this pure functional interface: given a typed [VendorReplyRequest] it returns the
 * same [SupplierEngine.Reply] the scripted engine produces, so `render`, the inbox, the negotiation
 * FSM, and every money gate downstream are byte-for-byte unchanged. In SCRIPTED mode the brain is
 * absent and [SupplierEngine.reply] is used as before.
 */
fun interface VendorBrain {
    /** Generate this vendor's reply to the buyer's RFQ/counter. Must never price below the floor. */
    fun reply(req: VendorReplyRequest): SupplierEngine.Reply
}

/** One prior message in a negotiation session, fed back to the vendor for progressive disclosure. */
data class SessionTurn(val fromBuyer: Boolean, val text: String)

/**
 * Everything a live vendor needs to negotiate one turn. Deliberately concrete (no nullable business
 * fields): the vendor sees its own economics, this buyer's dependence, the round, and the session so
 * far. The [persona] is the seeded ground truth; the model plays it but the harness never reads it.
 */
data class VendorReplyRequest(
    val supplierId: SupplierId,
    val supplierName: String,
    val persona: VendorPersona,
    val product: ProductConfig,
    /** 0 = opening RFQ, ≥1 = counters. */
    val round: Int,
    val day: Int,
    /** The buyer's raw message this turn. */
    val rfqBody: String,
    /** How dependent the buyer is on this vendor, 0 (hungry, must win it back) .. 1 (depended-on). */
    val dependence: Double,
    /** Prior turns this session (buyer offers + this vendor's own replies), oldest first. */
    val history: List<SessionTurn>,
)

/**
 * A live vendor's seeded persona (LIVE-SUPPLIERS-SPEC §5): everyone is greedy, spread across a range
 * wide enough to make a real buyer's market, and good-faith — [competence], not honesty, governs
 * whether an order actually ships on time and in full. Drawn once per (seed, vendor); deterministic.
 */
data class VendorPersona(
    /** Hard walk-away as a multiple of wholesale cost (× 1.05–1.15). Never sell below cost × this. */
    val floorMult: Double,
    /** Opening ambition / greed — the fraction of the buyer's headroom it reaches for (0.30–0.70). */
    val targetMargin: Double,
    /** Good-faith execution skill, ~Normal(0.75, 0.15) clamped [0.35, 0.98]. High ⇒ on time, in full. */
    val competence: Double,
) {
    companion object {
        /** Draw a vendor's persona from its own independent (seed, vendor) stream. */
        fun draw(seed: Long, id: SupplierId): VendorPersona {
            val rng = Rng.stream(seed, 0, "persona:${id.value}")
            val floorMult = 1.05 + 0.10 * rng.nextDouble()
            val targetMargin = 0.30 + 0.40 * rng.nextDouble()
            val competence = (0.75 + 0.15 * gaussian(rng)).coerceIn(0.35, 0.98)
            return VendorPersona(floorMult, targetMargin, competence)
        }

        /** Standard-normal draw via Box-Muller from the deterministic uniform stream. */
        private fun gaussian(rng: Rng): Double {
            // Guard the log against u1 == 0 (nextDouble is in [0,1)).
            val u1 = rng.nextDouble().coerceAtLeast(1e-12)
            val u2 = rng.nextDouble()
            return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2)
        }
    }
}
