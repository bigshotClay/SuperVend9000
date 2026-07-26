package dev.supervend.plan

import dev.supervend.model.ArtifactBinding
import dev.supervend.model.DayPhase
import dev.supervend.model.GateId
import dev.supervend.model.Mandate
import dev.supervend.model.Predicate
import dev.supervend.model.RoleId
import dev.supervend.model.SchemaRef
import dev.supervend.model.Severity
import dev.supervend.model.StepId
import dev.supervend.model.ValidatorRef
import kotlinx.serialization.Serializable

/**
 * A single plan step (software spec §4.1, amended by personas spec §5: every step is a
 * *persona-staffed* position, so `kind` is a [StepKind.PersonaStep] carrying a role — script vs.
 * model is a staffing detail of the persona, not of the step). The plan depends only on roles
 * (Dependency Inversion); staffing is bound at dispatch.
 */
@Serializable
data class StepDef(
    val id: StepId,
    val phase: DayPhase,
    val kind: StepKind,
    val mandate: Mandate,
    val preconditions: List<Predicate>,
    val inputs: List<ArtifactBinding>,
    val outputSchema: SchemaRef,
    val validator: ValidatorRef,
    val gates: List<GateId>,
    val retryBudget: Int = 2,
    val severityOnExhaustion: Severity,
    val consumers: List<StepId>,
) {
    val roleId: RoleId get() = kind.role
}

/**
 * Uniform staffing (personas spec §5). A step names a role; whether that role is filled by a pure
 * function or a model is recorded in the persona registry, not here. [firesOnlyOnTask] marks
 * conditional steps (e.g. PRO-05 fires only on a re-sourcing task) so the closure test doesn't
 * demand their preconditions hold every day.
 */
@Serializable
sealed interface StepKind {
    val role: RoleId

    @Serializable
    data class PersonaStep(val roleRef: String, val firesOnlyOnTask: Boolean = false) : StepKind {
        override val role: RoleId get() = RoleId(roleRef)
    }
}
