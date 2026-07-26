package dev.supervend.orchestrator

import dev.supervend.gates.GateRegistry
import dev.supervend.model.ActionType
import dev.supervend.model.GateDecision
import dev.supervend.model.ProposedAction
import dev.supervend.plan.StepDef

/**
 * The single writer (CLAUDE.md #10). Every state mutation the run makes flows through here, and the
 * gate check and the write apply happen atomically inside this one actor — there is no path to
 * mutate state that skips its gates. A step submits its proposed actions; the actor runs *that
 * step's* declared gates over each action, logs every fire (PRD R26), applies the action on a clean
 * sweep, and stops at the first rejection without applying it (that becomes a GATE_REJECTED
 * incident upstream).
 */
class StateActor(
    private val gates: GateRegistry,
    private val world: StepWorld,
    private val gateLog: MutableList<GateLogEntry>,
) {
    /**
     * Submit a step's write intents. Returns the first rejection encountered (state left with only
     * the actions applied before it), or `null` if every action cleared and was applied.
     */
    fun submit(step: StepDef, actions: List<ProposedAction>): SubmitResult {
        var economicApplied = 0
        for (action in actions) {
            for (gateId in step.gates) {
                val decision = gates.require(gateId).evaluate(action, world)
                gateLog += gateEntry(step, action, gateId.value, decision)
                if (decision is GateDecision.Reject) {
                    return SubmitResult(reject = decision, economicApplied = economicApplied)
                }
            }
            // Every declared gate allowed → apply atomically within the actor.
            world.apply(action)
            if (action.actionType.isEconomic()) economicApplied++
        }
        return SubmitResult(reject = null, economicApplied = economicApplied)
    }

    private fun gateEntry(step: StepDef, action: ProposedAction, gateId: String, decision: GateDecision): GateLogEntry =
        when (decision) {
            is GateDecision.Allow -> GateLogEntry(
                day = world.currentDay(), stepId = step.id, actor = step.roleId,
                actionType = action.actionType, gateId = gateId, allowed = true,
                reason = "", expected = "", observed = "",
            )
            is GateDecision.Reject -> GateLogEntry(
                day = world.currentDay(), stepId = step.id, actor = step.roleId,
                actionType = action.actionType, gateId = gateId, allowed = false,
                reason = decision.reason, expected = decision.expected, observed = decision.observed,
            )
        }
}

/** Outcome of a [StateActor.submit]: the first rejection (if any) and how many economic writes landed. */
data class SubmitResult(val reject: GateDecision.Reject?, val economicApplied: Int)

/** Money-mutating action types drive stall detection's "steps without economic action" (PRD R42). */
internal fun ActionType.isEconomic(): Boolean = when (this) {
    ActionType.SET_PRICE, ActionType.PLACE_ORDER,
    ActionType.WRITE_CASH_LEDGER, ActionType.WRITE_INVENTORY_LEDGER,
    ActionType.COLLECT_CASH, ActionType.MOVE_INVENTORY,
    -> true
    ActionType.SEND_EMAIL, ActionType.SUBMIT_TASK, ActionType.PROPOSE_AMENDMENT,
    ActionType.APPLY_AMENDMENT, ActionType.WRITE_RECOVERY_ANNOTATION,
    -> false
}
