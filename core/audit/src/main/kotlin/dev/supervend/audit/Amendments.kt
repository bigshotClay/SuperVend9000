package dev.supervend.audit

import dev.supervend.model.Hashing
import dev.supervend.model.Sha256
import dev.supervend.validate.Amendment
import dev.supervend.validate.AmendmentProposal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The bounded-amendment thresholds (workplan AUD-03 "amendment bounds gates"). A hashed config
 * artifact like [dev.supervend.gates.GateConfig] (CLAUDE.md #11); an out-of-bounds proposal is
 * rejected with a logged reason, never applied — the audit agent cannot rewrite the plan, only
 * nudge pre-registered parameters within these limits.
 */
@Serializable
data class AmendmentBounds(
    /** Max absolute price-band move per amendment, basis points. */
    val maxBandDeltaBps: Long = 2_000,
    /** Max amendments applied in a single audit. */
    val maxPerAudit: Int = 5,
) {
    fun canonicalJson(): String = CANONICAL.encodeToString(serializer(), this)
    fun hash(): Sha256 = Hashing.sha256(canonicalJson())

    /** Bounds check for one amendment (business layer of AUD-03). */
    fun check(amendment: Amendment): AmendmentVerdict = when (amendment) {
        is Amendment.PriceBandAdjust ->
            if (kotlin.math.abs(amendment.deltaBps) <= maxBandDeltaBps) AmendmentVerdict.Accepted
            else AmendmentVerdict.Rejected("|deltaBps| ${amendment.deltaBps} exceeds bound $maxBandDeltaBps")
        // The mix/supplier variants are structurally bounded (sealed set); no numeric bound to breach.
        is Amendment.ProductMixChange -> AmendmentVerdict.Accepted
        is Amendment.SupplierListChange -> AmendmentVerdict.Accepted
    }

    companion object {
        private val CANONICAL = Json { prettyPrint = false; encodeDefaults = true }
        fun default(): AmendmentBounds = AmendmentBounds()
    }
}

@Serializable
sealed interface AmendmentVerdict {
    @Serializable data object Accepted : AmendmentVerdict
    @Serializable data class Rejected(val reason: String) : AmendmentVerdict
}
