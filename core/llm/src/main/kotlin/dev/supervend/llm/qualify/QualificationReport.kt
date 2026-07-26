package dev.supervend.llm.qualify

import dev.supervend.model.Sha256
import kotlinx.serialization.Serializable

@Serializable
data class CaseResult(val caseId: String, val category: BatteryCase.Category, val passed: Boolean)

/**
 * The outcome of running the battery against one model. Persisted to the `qualification` table;
 * the [batteryHash] and [score] enter the manifest per slot (personas §7, CLAUDE.md #11).
 * [qualified] gates entry to the experiment matrix at the >90% threshold.
 */
@Serializable
data class QualificationReport(
    val modelId: String,
    val batteryHash: Sha256,
    val cases: List<CaseResult>,
    val threshold: Double,
) {
    val total: Int get() = cases.size
    val passed: Int get() = cases.count { it.passed }
    val score: Double get() = if (total == 0) 0.0 else passed.toDouble() / total
    val qualified: Boolean get() = score > threshold

    /** Per-category pass rates — the free per-seat difficulty signal personas §7 calls out. */
    fun byCategory(): Map<BatteryCase.Category, Double> =
        cases.groupBy { it.category }.mapValues { (_, cs) -> cs.count { it.passed }.toDouble() / cs.size }
}
