package dev.supervend.model

/**
 * An immutable snapshot of the facts gates and validators may read (software spec §6, §7). It is
 * an interface so gates stay pure functions of (action, view): no clock, no I/O, no randomness,
 * hence property-testable and bit-reproducibly replayable by `revalidate` (CLAUDE.md #4). The
 * runtime supplies a snapshot captured inside the StateActor; tests supply a plain value holder.
 *
 * Contextual facts (is this supplier validated? what is the cost basis? how much have we spent
 * today?) live here — never in model free-text (PRD R19). Intrinsic facts of a proposal (the
 * price, the units) live on the [ProposedAction] itself.
 */
interface StateView {
    /** Wholesale cost basis for margin math (PRD R18). */
    fun costBasis(product: ProductId): Cents

    /** True iff the supplier is in the validated registry (PRD R19). */
    fun supplierValidated(supplier: SupplierId): Boolean

    /** True iff an order spec artifact is on file for this payee/product before payment (PRD R20). */
    fun specOnFile(supplier: SupplierId, product: ProductId): Boolean

    /** Total discretionary spend already committed today, for the daily cap (PRD R21). */
    fun spendCommittedToday(): Cents

    /** The researched sanity band for a product's unit cost (PRD R23). */
    fun sanityBand(product: ProductId): SanityBand
}

/** Researched acceptable unit-cost range for a product (software spec §6, PRD R23). */
data class SanityBand(val low: Cents, val high: Cents) {
    init {
        require(low <= high) { "sanity band low $low must be ≤ high $high" }
    }

    fun contains(unitCost: Cents): Boolean = unitCost in low..high
}
