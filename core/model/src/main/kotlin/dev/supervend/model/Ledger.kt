package dev.supervend.model

import kotlinx.serialization.Serializable

/**
 * Append-only ledger model (software spec §5.1). Balances are computed *projections* over
 * the immutable journal — never a stored mutable number. The projection helpers here are
 * pure folds so the "projection = fold of journal" invariant is directly property-testable.
 */

/**
 * Provenance of a ledger entry. Sealed so the projection can decide, exhaustively, which
 * sources move money/inventory.
 *
 * [RecoveryAnnotation] entries are informational only — they NEVER contribute to a balance
 * projection. This is PRD R24 ("recovery may only add information, never plug the books")
 * expressed at the data-model level: even a row recovery is allowed to append cannot change
 * a balance. The complementary compile-time guarantee (recovery cannot append the *other*
 * sources, nor mutate existing rows) lives in `core/state`'s `RecoveryStateWriter`.
 */
@Serializable
sealed interface EntrySource {
    /** A real economic movement reported by an environment tool result. Moves balances. */
    @Serializable data object SimTool : EntrySource
    /** An entry produced by the reconciler. Moves balances; by policy never a plug. */
    @Serializable data object Reconciliation : EntrySource
    /** Recovery-added information: annotations/flags. Never moves a balance. */
    @Serializable data class RecoveryAnnotation(val incident: IncidentId, val note: String) : EntrySource

    /** True iff entries with this source contribute to a balance projection. */
    val movesBalance: Boolean
        get() = when (this) {
            SimTool, Reconciliation -> true
            is RecoveryAnnotation -> false
        }
}

@Serializable
data class CashLedgerEntry(
    val seq: Long,
    val day: Int,
    val amount: Cents,
    val source: EntrySource,
    val disputed: Boolean,
    val memo: String,
)

@Serializable
data class InventoryLedgerEntry(
    val seq: Long,
    val day: Int,
    val product: ProductId,
    val location: Location,
    val units: Int,
    val source: EntrySource,
    val disputed: Boolean,
    val memo: String,
)

/** Pure projections over ledger journals. No I/O, no clock, no randomness — replayable. */
object Ledgers {

    /**
     * Spendable cash = fold of all entries whose source moves balances, excluding disputed
     * rows. Disputed entries are held out of the spendable balance (spec §5.1): a
     * discrepancy is quarantined, never silently counted.
     */
    fun spendableCash(entries: List<CashLedgerEntry>): Cents =
        Cents.sum(entries.filter { it.source.movesBalance && !it.disputed }.map { it.amount })

    /** Units on hand for one (product, location), same fold discipline as cash. */
    fun onHand(entries: List<InventoryLedgerEntry>, product: ProductId, location: Location): Int =
        entries.filter {
            it.source.movesBalance && !it.disputed && it.product == product && it.location == location
        }.sumOf { it.units }
}
