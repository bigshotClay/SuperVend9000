package dev.supervend.orchestrator

import dev.supervend.model.ActionType
import dev.supervend.model.PlanCounterSnapshot
import dev.supervend.model.RoleId
import dev.supervend.model.StepId
import dev.supervend.recovery.IncidentRecord
import dev.supervend.recovery.RecoveryOutcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The run's three golden logs (Stage 5 DoD): the [PlanCounterSnapshot] trace, the gate-fire log,
 * and the incident log. All serializable and deterministically ordered, so a MockModel run emits
 * byte-stable files that a golden test diffs against a committed baseline. This is the harness's
 * externalized memory made auditable — the whole point of the thesis.
 */
@Serializable
data class RunTrace(
    val counterTrace: List<PlanCounterSnapshot> = emptyList(),
    val gateLog: List<GateLogEntry> = emptyList(),
    val incidentLog: List<IncidentRecord> = emptyList(),
    val recoveryLog: List<RecoveryLogEntry> = emptyList(),
    val stallResets: List<StallReset> = emptyList(),
) {
    fun canonicalJson(): String = PRETTY.encodeToString(serializer(), this)

    companion object {
        private val PRETTY = Json { prettyPrint = true; encodeDefaults = true }
    }
}

/**
 * One gate fire (PRD R26: reason, actor, step, payload). Logged whether the gate allowed or
 * rejected, so the golden gate log proves the invariant "no ungated money path" was actually
 * exercised — a zero-reject run and a never-checked run are distinguishable.
 */
@Serializable
data class GateLogEntry(
    val day: Int,
    val stepId: StepId,
    val actor: RoleId,
    val actionType: ActionType,
    val gateId: String,
    val allowed: Boolean,
    val reason: String,
    val expected: String,
    val observed: String,
)

/** Recovery invocation record (software spec §10 step 3: the chosen verdict is logged). */
@Serializable
data class RecoveryLogEntry(
    val day: Int,
    val incidentId: String,
    val priorInvocations: Int,
    val outcome: RecoveryOutcome,
)

/** A stall-forced hard reset of the program counter (PRD R42). */
@Serializable
data class StallReset(
    val day: Int,
    val stepIndex: Int,
    val trigger: StallTrigger,
    val counterValue: Int,
)

@Serializable
enum class StallTrigger {
    /** N consecutive failed/no-op step outcomes. */
    CONSECUTIVE_FAILURES,

    /** M steps elapsed without an economic (money-mutating) action being applied. */
    NO_ECONOMIC_ACTION,
}
