package dev.supervend.recovery

import dev.supervend.model.StepId
import kotlinx.serialization.Serializable

/**
 * The recovery pipeline (software spec §10, PRD R40). Invoked synchronously by the orchestrator
 * when a BLOCKING incident pauses the loop. It does not reason openly: it classifies the incident
 * against harness-computed candidate resume points, enforces a per-incident invocation cap, and
 * escalates to the weekly-audit queue when the cap is exhausted or no candidate fits.
 *
 * The pipeline itself is pure w.r.t. the classifier seam — no clock, no I/O — so an incident replay
 * (`revalidate`) with the same recorded classifier decision reproduces the outcome bit-identically.
 */
class RecoveryPipeline(
    private val classifier: RecoveryClassifier,
    /** Per-incident recovery-invocation cap (software spec §10 default 2). */
    val cap: Int = 2,
) {
    /**
     * @param priorInvocations how many times this same incident scope has already been through
     *   recovery. At [cap] or beyond, recovery is not attempted again — the incident escalates.
     */
    fun handle(
        incident: IncidentRecord,
        candidates: List<StepId>,
        priorInvocations: Int,
    ): RecoveryOutcome {
        if (priorInvocations >= cap) {
            return RecoveryOutcome.Escalated(incident.id.value, EscalationReason.CAP_EXHAUSTED)
        }
        val decision = classifier.classify(incident, candidates)
        val resume = decision.resumeAt
        // Validate the choice: a named resume point must be one the harness actually offered.
        if (resume is ResumePoint.At && resume.step !in candidates) {
            return RecoveryOutcome.Escalated(incident.id.value, EscalationReason.RESUME_NOT_A_CANDIDATE)
        }
        if (resume is ResumePoint.Escalate) {
            return RecoveryOutcome.Escalated(incident.id.value, EscalationReason.NO_CANDIDATE_FITS)
        }
        return RecoveryOutcome.Resolved(decision)
    }
}

/** Outcome of one recovery attempt. Logged verbatim (software spec §10 step 3). */
@Serializable
sealed interface RecoveryOutcome {
    @Serializable data class Resolved(val decision: RecoveryDecision) : RecoveryOutcome
    @Serializable data class Escalated(val incidentId: String, val reason: EscalationReason) : RecoveryOutcome
}

@Serializable
enum class EscalationReason { CAP_EXHAUSTED, NO_CANDIDATE_FITS, RESUME_NOT_A_CANDIDATE }
