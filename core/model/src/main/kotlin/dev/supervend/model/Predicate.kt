package dev.supervend.model

import kotlinx.serialization.Serializable

/**
 * Precondition predicate language (software spec §4.2). Deliberately NOT a general
 * expression language — a closed sealed set, each variant machine-checkable against the
 * state store. Adding a variant is a code change with tests; the plan format cannot express
 * a check the harness has not implemented. This rigidity is intentional.
 */
@Serializable
sealed interface Predicate {
    @Serializable data class CashAtLeast(val amount: Cents) : Predicate
    @Serializable data class ArtifactExists(val binding: ArtifactBinding) : Predicate
    @Serializable data class ArtifactValidated(val binding: ArtifactBinding) : Predicate
    @Serializable data class LedgerReconciled(val ledger: LedgerId, val asOfDay: Int) : Predicate
    @Serializable data class NoOpenBlockingIncidents(val scope: Scope) : Predicate
    @Serializable data class InventoryAtLeast(val product: ProductId, val units: Int, val location: Location) : Predicate
    @Serializable data class OrderInState(val order: OrderId, val state: OrderState) : Predicate
    @Serializable data class DayPhaseIs(val phase: DayPhase) : Predicate
}

/** Result of evaluating one predicate against state — carried in incident records. */
@Serializable
data class PredicateResult(
    val predicate: Predicate,
    val held: Boolean,
    val observed: String,
)
