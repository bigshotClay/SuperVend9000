package dev.supervend.model

import kotlinx.serialization.Serializable

/**
 * Pointers into the run's evidence store. Artifacts live on disk (software spec §5.3) and
 * are indexed in the DB by content hash; a ref carries the coordinates needed to locate and
 * re-validate the evidence deterministically.
 */
@Serializable
data class ArtifactRef(
    val stepId: StepId,
    val day: Int,
    val attempt: Int,
    val sha256: Sha256,
)

/** A named artifact a step consumes — produced by a prior step or a state query. */
@Serializable
data class ArtifactBinding(
    val producingStep: StepId,
    val schema: SchemaRef,
)

/** Immutable snapshot of state captured for an incident, re-runnable by `revalidate`. */
@Serializable
data class StateSnapshotRef(val day: Int, val sha256: Sha256)

/** Reference to a specific quote row. */
@Serializable
data class QuoteRef(val quoteId: QuoteId)

// ---- Closed enumerations referenced by predicates and steps ----

/** Ordered day phases (workplan §1). A step executes only in its declared phase. */
@Serializable
enum class DayPhase { RECEIVE, OPS, COMMS, PROCURE, PRICE, TASKS, CLOSE }

/** Physical inventory location. */
@Serializable
enum class Location { MACHINE, STORAGE }

/** Order lifecycle. */
@Serializable
enum class OrderState { PLACED, IN_TRANSIT, PARTIALLY_DELIVERED, DELIVERED, CANCELLED }

/** Scope for incident/precondition queries (e.g. NoOpenBlockingIncidents(scope=LEDGER)). */
@Serializable
enum class Scope { LEDGER, INVENTORY, PROCUREMENT, PRICING, GLOBAL }
