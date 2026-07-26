package dev.supervend.recovery

import dev.supervend.model.RecoveryVerdict
import dev.supervend.model.StepId
import kotlinx.serialization.Serializable

/**
 * The recovery agent's whole output (software spec §10, PRD R40): a classification verdict plus a
 * resume point chosen from harness-computed candidates. This is *multiple choice, not open
 * reasoning* — the agent picks a [RecoveryVerdict] variant and either names one candidate step or
 * escalates. Validated like any artifact; an out-of-set [ResumePoint.At] is a validation failure.
 */
@Serializable
data class RecoveryDecision(
    val verdict: RecoveryVerdict,
    val resumeAt: ResumePoint,
)

/**
 * Where the loop resumes. Sealed rather than a nullable `StepId?` (CLAUDE.md #3): [At] names a
 * harness-computed candidate; [Escalate] means no candidate fits and the incident goes to the
 * weekly-audit queue via write-down (PRD R40).
 */
@Serializable
sealed interface ResumePoint {
    @Serializable data class At(val step: StepId) : ResumePoint
    @Serializable data object Escalate : ResumePoint
}

/** The model seam: given evidence and candidates, choose a decision. Deterministic in tests. */
fun interface RecoveryClassifier {
    fun classify(incident: IncidentRecord, candidates: List<StepId>): RecoveryDecision
}
