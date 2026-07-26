package dev.supervend.orchestrator

import dev.supervend.model.Cents
import dev.supervend.model.Hashing
import dev.supervend.model.Predicate
import dev.supervend.model.PredicateResult
import dev.supervend.model.ProductId
import dev.supervend.model.ProposedAction
import dev.supervend.model.SanityBand
import dev.supervend.model.Severity
import dev.supervend.model.StateSnapshotRef
import dev.supervend.model.SupplierId
import dev.supervend.plan.StepDef

internal val SUP = SupplierId("SUP-HC")
internal val COLA = ProductId("COLA")

/**
 * A deterministic, clock-free [StepWorld] for the orchestrator's model-free golden runs and unit
 * tests. Everything is a stable value: preconditions hold by default (overridable per test to force
 * a mismatch), gate facts are satisfiable, and the state snapshot is content-addressed by day so a
 * replay reproduces the same [StateSnapshotRef]. Applied actions and queued tasks are recorded for
 * assertions.
 */
internal class TestWorld(
    private val day: Int = 1,
    private val preconditionHolds: (Predicate) -> Boolean = { true },
    private val conditionalFires: (StepDef) -> Boolean = { false },
) : StepWorld {
    val applied = mutableListOf<ProposedAction>()
    val tasks = mutableListOf<Pair<String, Severity>>()

    // StateView — satisfiable gate facts.
    override fun costBasis(product: ProductId): Cents = Cents(70)
    override fun supplierValidated(supplier: SupplierId): Boolean = supplier == SUP
    override fun specOnFile(supplier: SupplierId, product: ProductId): Boolean = true
    override fun spendCommittedToday(): Cents = Cents(0)
    override fun sanityBand(product: ProductId): SanityBand = SanityBand(Cents(0), Cents(1000))

    // ValidationState — referential checks resolve.
    override fun productExists(product: ProductId): Boolean = true
    override fun supplierExists(supplier: SupplierId): Boolean = true

    // StepWorld.
    override fun evaluate(predicate: Predicate): PredicateResult =
        PredicateResult(predicate, held = preconditionHolds(predicate), observed = "test")

    override fun currentDay(): Int = day
    override fun taskAvailableFor(step: StepDef): Boolean = conditionalFires(step)
    override fun apply(action: ProposedAction) { applied += action }
    override fun enqueueTask(kind: String, severity: Severity) { tasks += kind to severity }
    override fun snapshotRef(): StateSnapshotRef = StateSnapshotRef(day, Hashing.sha256("snap-$day"))
}
