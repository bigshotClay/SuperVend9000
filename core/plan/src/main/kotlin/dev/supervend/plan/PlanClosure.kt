package dev.supervend.plan

import dev.supervend.model.ActionType
import dev.supervend.model.Predicate
import dev.supervend.model.Severity

/**
 * The plan-closure checks (workplan §6 invariants, as executable predicates). A well-formed plan
 * yields zero violations; CI fails on any. These are pure over the plan value — no registries
 * needed for the intra-plan checks. The cross-registry checks (gate/validator/role resolution
 * against the *actual* registries) live in the app integration test, which can see every module.
 */
object PlanClosure {

    /** The eight registered gate ids (software spec §6). Kept here so the intra-plan check is self-contained. */
    val KNOWN_GATES: Set<String> = setOf(
        "MarginFloor", "ValidatedPayee", "SpecBeforePay", "SpendCap",
        "ConcessionPolicy", "QuoteSanity", "RecoveryWrite", "LedgerConsistency", "TaskMandate",
    )

    /** Actions that write cash, orders, or prices — no ungated path may perform one (workplan §6.2). */
    private val MONEY_MUTATING = setOf(
        ActionType.PLACE_ORDER, ActionType.SET_PRICE,
        ActionType.WRITE_CASH_LEDGER, ActionType.WRITE_INVENTORY_LEDGER,
    )

    fun check(plan: Plan): List<String> {
        val violations = mutableListOf<String>()
        val stepIds = plan.stepIds().map { it.value }.toSet()

        for (step in plan.allSteps) {
            val sid = step.id.value

            // Invariant 4: roles resolve.
            if (step.roleId.value !in Roles.all) {
                violations += "$sid names unknown role ${step.roleId.value}"
            }
            // Invariant 4: gates resolve.
            for (g in step.gates) {
                if (g.value !in KNOWN_GATES) violations += "$sid names unknown gate ${g.value}"
            }
            // Invariant 4: validators (== artifact schema) resolve.
            if (step.validator.id !in Artifacts.all) {
                violations += "$sid names unknown validator ${step.validator.id}"
            }
            if (step.outputSchema.id !in Artifacts.all) {
                violations += "$sid emits unknown schema ${step.outputSchema.id}"
            }
            // Consumers resolve to real steps.
            for (c in step.consumers) {
                if (c.value !in stepIds) violations += "$sid lists unknown consumer ${c.value}"
            }
            // Precondition artifact bindings reference real steps.
            for (p in step.preconditions) {
                val producing = when (p) {
                    is Predicate.ArtifactValidated -> p.binding.producingStep.value
                    is Predicate.ArtifactExists -> p.binding.producingStep.value
                    else -> null
                }
                if (producing != null && producing !in stepIds) {
                    violations += "$sid precondition references unknown step $producing"
                }
            }

            // Invariant 2 (OPERATOR-INPUT #10, resolved): no ungated path writes cash, orders, or
            // prices — discretionary money AND ledger-mirror writes must both be gated.
            if (step.mandate.allowed.any { it in MONEY_MUTATING } && step.gates.isEmpty()) {
                violations += "$sid writes cash/orders/prices but has no gate"
            }

            // Invariant 1: no BLOCKING step depends on model accuracy (BOOT model steps excepted).
            val isModelSeat = step.roleId.value in Roles.modelSeats
            val isBoot = sid.startsWith("BOOT-")
            if (isModelSeat && step.severityOnExhaustion == Severity.BLOCKING && !isBoot) {
                violations += "$sid is a BLOCKING model-staffed step outside bootstrap (invariant 1)"
            }
        }
        return violations
    }
}
