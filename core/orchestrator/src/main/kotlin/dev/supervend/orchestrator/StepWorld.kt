package dev.supervend.orchestrator

import dev.supervend.model.Predicate
import dev.supervend.model.PredicateResult
import dev.supervend.model.ProposedAction
import dev.supervend.model.Severity
import dev.supervend.model.StateSnapshotRef
import dev.supervend.model.StateView
import dev.supervend.plan.StepDef
import dev.supervend.validate.ValidationState

/**
 * The orchestrator's whole window onto run state. It unifies the two read surfaces the driver needs
 * — [StateView] for gates and [evaluate] for preconditions — plus the narrow write/side-effect
 * surface (apply an allowed action, queue a task, snapshot for an incident). Everything the driver
 * does to the world goes through this one interface, so a run is fully reproducible from a captured
 * world and the driver holds no hidden state of its own.
 *
 * Precondition evaluation lives *here*, not in the driver: `evaluate` is the "script" of software
 * spec §9 / §10 that machine-checks a [Predicate] against the store. The driver only sequences.
 */
interface StepWorld : StateView, ValidationState {
    /** Evaluate one precondition against current state (pure w.r.t. the snapshot; PRD R39 evidence). */
    fun evaluate(predicate: Predicate): PredicateResult

    fun currentDay(): Int

    /**
     * Whether a conditional step (`firesOnlyOnTask`) has a triggering task in the queue. The driver
     * skips a conditional step when this is false rather than treating its unmet inputs as a failure.
     */
    fun taskAvailableFor(step: StepDef): Boolean

    /** Apply an action the [StateActor] has already cleared through its gates. */
    fun apply(action: ProposedAction)

    /** Insert a task (NON_BLOCKING severity routing, PRD R41). */
    fun enqueueTask(kind: String, severity: Severity)

    /** A content ref to the current state, captured into incident records for replay. */
    fun snapshotRef(): StateSnapshotRef
}

/**
 * Verifies a step's preconditions against the world (software spec §9). Returns the per-predicate
 * results so a mismatch can be captured as incident evidence (PRD R39) rather than a bare boolean.
 * `DayPhaseIs` is implicit on every step (workplan §1) and is not carried in `StepDef.preconditions`.
 */
object PreconditionVerifier {
    fun verify(preconditions: List<Predicate>, world: StepWorld): List<PredicateResult> =
        preconditions.map { world.evaluate(it) }

    fun allHeld(results: List<PredicateResult>): Boolean = results.all { it.held }
}
