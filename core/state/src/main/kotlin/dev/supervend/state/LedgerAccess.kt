package dev.supervend.state

import dev.supervend.model.Cents
import dev.supervend.model.IncidentId
import dev.supervend.model.Location
import dev.supervend.model.ProductId

/**
 * Segregated ledger interfaces (software spec §5.1, PRD R24, CLAUDE.md #5).
 *
 * The point of splitting these is the compile-time property: the recovery module is handed
 * *only* a [RecoveryStateWriter], whose surface contains no balance-moving append and no
 * destructive method. It is structurally incapable of altering a balance — it can add
 * annotations and dispute markers and nothing else.
 */

interface CashLedgerReader {
    fun cashEntries(): List<dev.supervend.model.CashLedgerEntry>
    /** Spendable cash = projection over the journal (non-disputed, balance-moving sources). */
    fun spendableCash(): Cents
}

interface InventoryLedgerReader {
    fun inventoryEntries(): List<dev.supervend.model.InventoryLedgerEntry>
    fun onHand(product: ProductId, location: Location): Int
}

/**
 * Balance-moving writes. Bookkeeper / Reconciler seats only. The source is restricted to the
 * two balance-moving provenances; recovery annotations cannot be written through here.
 */
interface LedgerWriter {
    fun appendCash(day: Int, amount: Cents, source: LedgerSource, memo: String): Long
    fun appendInventory(
        day: Int,
        product: ProductId,
        location: Location,
        units: Int,
        source: LedgerSource,
        memo: String,
    ): Long
}

/** The two provenances that may move a balance. Subset of the model's `EntrySource`. */
enum class LedgerSource { SIM_TOOL, RECONCILIATION }

/**
 * Additive-only recovery surface (PRD R24). Every method appends information; none mutates a
 * balance, overwrites a disputed value, or deletes. Disputing is itself an append (a marker
 * row), so "set disputed flag" never becomes an in-place mutation.
 */
interface RecoveryStateWriter {
    /** Append an informational annotation row (amount/units = 0; never moves a balance). */
    fun annotateCash(day: Int, incident: IncidentId, note: String): Long
    fun annotateInventory(
        day: Int,
        product: ProductId,
        location: Location,
        incident: IncidentId,
        note: String,
    ): Long

    /** Mark an existing cash entry disputed by appending a dispute marker. */
    fun disputeCash(targetSeq: Long, incident: IncidentId, day: Int, note: String)
    /** Mark an existing inventory entry disputed by appending a dispute marker. */
    fun disputeInventory(targetSeq: Long, incident: IncidentId, day: Int, note: String)
}
