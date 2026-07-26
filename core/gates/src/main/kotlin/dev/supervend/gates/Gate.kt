package dev.supervend.gates

import dev.supervend.model.BasisPoints
import dev.supervend.model.Cents
import dev.supervend.model.GateDecision
import dev.supervend.model.GateId
import dev.supervend.model.Hashing
import dev.supervend.model.ProposedAction
import dev.supervend.model.Sha256
import dev.supervend.model.StateView
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A pre-write gate (software spec §6). Pure: a function of (action, immutable state snapshot).
 * No clock, no I/O, no randomness — every evaluation is property-testable and replayable
 * bit-identically by `revalidate` (CLAUDE.md #4). A gate that does not apply to a given action
 * returns [GateDecision.Allow] (it has no opinion); relevance is decided by the plan's gate list.
 */
interface Gate {
    val id: GateId
    fun evaluate(action: ProposedAction, state: StateView): GateDecision
}

/** Convenience for a uniform rejection carrying the `expected` vs `observed` strings (spec §6). */
internal fun GateId.reject(reason: String, expected: String, observed: String): GateDecision =
    GateDecision.Reject(this, reason, expected, observed)

/**
 * The versioned threshold artifact (software spec §6, CLAUDE.md #11). Hashed into the run manifest;
 * every numeric policy the gate suite enforces lives here so a run is fully attributable and a
 * threshold change is a re-hash, never a code change. Defaults are proposed (OPERATOR-INPUT #11).
 */
@Serializable
data class GateConfig(
    /** Minimum margin over cost basis, in basis points (2500 = 25%). PRD R18. */
    val marginFloorBps: BasisPoints,
    /** Single-order spend cap. PRD R21. */
    val spendCapPerOrder: Cents,
    /** Total daily discretionary spend cap. PRD R21. */
    val spendCapPerDay: Cents,
    /** MAD outlier multiplier, in basis points (30000 = 3.0×). PRD R23. */
    val madMultiplierBps: BasisPoints,
    /** Concession/discount ceiling, in basis points (500 = 5%). PRD R22. */
    val concessionCeilingBps: BasisPoints,
) {
    fun canonicalJson(): String = CANONICAL.encodeToString(serializer(), this)
    fun hash(): Sha256 = Hashing.sha256(canonicalJson())

    companion object {
        private val CANONICAL = Json { prettyPrint = false; encodeDefaults = true }

        /** Proposed M1 defaults (OPERATOR-INPUT #11) — flagged for review, not spec-mandated. */
        fun default(): GateConfig = GateConfig(
            marginFloorBps = BasisPoints.percent(25),
            spendCapPerOrder = Cents(15_000),   // $150.00
            spendCapPerDay = Cents(30_000),     // $300.00
            madMultiplierBps = BasisPoints(30_000), // 3.0×
            concessionCeilingBps = BasisPoints.percent(5),
        )
    }
}
