package dev.supervend.model

import kotlinx.serialization.Serializable

/**
 * The orchestrator's program counter (software spec §9). A run walks the plan deterministically;
 * `(runId, day, stepIndex, status)` is the resumable position. Snapshots are captured into incident
 * records (spec §10) and streamed into the PlanCounter trace, the primary golden artifact for the
 * orchestrator's determinism.
 */
@Serializable
data class PlanCounterSnapshot(
    val runId: RunId,
    val day: Int,
    val stepIndex: Int,
    val stepId: StepId,
    val status: StepStatus,
)

/**
 * Terminal status of a step attempt as recorded by the driver. A closed set: adding a status is a
 * code change that forces every trace consumer to handle it. [SKIPPED] is a conditional step whose
 * task trigger was absent; [RESET] marks a stall-forced hard reset of the counter (PRD R42).
 */
@Serializable
enum class StepStatus {
    SUCCESS,
    VALIDATION_FAILED,
    PRECONDITION_MISMATCH,
    AGENT_UNUSABLE,
    GATE_REJECTED,
    SKIPPED,
    RESET,
}
