package dev.supervend.recovery

import dev.supervend.model.ArtifactRef
import dev.supervend.model.FailureReport
import dev.supervend.model.GateDecision
import dev.supervend.model.IncidentId
import dev.supervend.model.PlanCounterSnapshot
import dev.supervend.model.PredicateResult
import dev.supervend.model.Severity
import dev.supervend.model.StateSnapshotRef
import dev.supervend.model.UnusableReason
import dev.supervend.plan.StepDef
import kotlinx.serialization.Serializable

/**
 * The structured incident record the harness pre-assembles for every failure (PRD R39, software
 * spec §10). It is *evidence, not a transcript*: because artifacts and validators are deterministic
 * and pure (CLAUDE.md #4), every field can be re-derived and re-checked, so `revalidate` reproduces
 * the failure verdict bit-identically rather than re-interpreting model output.
 *
 * Deviation from the spec's illustrative `artifact: ArtifactRef?` / `failureReport: FailureReport?`
 * pair: CLAUDE.md #3 forbids nullable business fields, so the optional evidence is a sealed
 * [FailureEvidence] whose three variants are exactly the three ways a step fails.
 */
@Serializable
data class IncidentRecord(
    val id: IncidentId,
    val day: Int,
    val counter: PlanCounterSnapshot,
    val stepDef: StepDef,
    val preconditionResults: List<PredicateResult>,
    val failure: FailureEvidence,
    val gateRejections: List<GateDecision.Reject>,
    val stateSnapshot: StateSnapshotRef,
    val severity: Severity,
)

/**
 * What went wrong at the failing step — the sealed replacement for the spec's nullable
 * `artifact`/`failureReport`. [NoArtifact] is a precondition mismatch (nothing was produced);
 * [ValidationFailure] carries the failed artifact and its failure report (re-runnable evidence);
 * [UnusableOutput] is a model that produced nothing parseable.
 */
@Serializable
sealed interface FailureEvidence {
    @Serializable data object NoArtifact : FailureEvidence
    @Serializable data class ValidationFailure(val artifact: ArtifactRef, val report: FailureReport) : FailureEvidence
    @Serializable data class UnusableOutput(val reason: UnusableReason) : FailureEvidence
}
