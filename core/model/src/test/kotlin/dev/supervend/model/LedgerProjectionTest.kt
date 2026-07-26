package dev.supervend.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

/**
 * Pure projection invariants (software spec §5.1, §13). These hold on the fold helpers with
 * no DB involved — the storage layer's job is only to reproduce these entries faithfully.
 */
class LedgerProjectionTest : FunSpec({

    val sources: Arb<EntrySource> = Arb.element(
        EntrySource.SimTool,
        EntrySource.Reconciliation,
        EntrySource.RecoveryAnnotation(IncidentId("i"), "note"),
    )

    val cashEntry: Arb<CashLedgerEntry> = Arb.bind(
        Arb.long(-100_000L..100_000L),
        sources,
        Arb.boolean(),
    ) { amount, source, disputed ->
        // RecoveryAnnotation rows carry no money (enforced by the DB CHECK too).
        val amt = if (source is EntrySource.RecoveryAnnotation) 0L else amount
        CashLedgerEntry(seq = 0, day = 1, amount = Cents(amt), source = source, disputed = disputed, memo = "")
    }

    test("spendableCash = fold of non-disputed, balance-moving entries") {
        checkAll(Arb.list(cashEntry, 0..40)) { entries ->
            val expected = Cents.sum(
                entries.filter { it.source.movesBalance && !it.disputed }.map { it.amount },
            )
            Ledgers.spendableCash(entries) shouldBe expected
        }
    }

    test("disputing an entry never increases the balance") {
        checkAll(Arb.list(cashEntry, 1..30)) { entries ->
            val undisputed = entries.map { it.copy(disputed = false) }
            val withOneDisputed = undisputed.mapIndexed { i, e -> if (i == 0) e.copy(disputed = true) else e }
            val before = Ledgers.spendableCash(undisputed).value
            val after = Ledgers.spendableCash(withOneDisputed).value
            (after <= before || undisputed[0].amount.isNegative || !undisputed[0].source.movesBalance) shouldBe true
        }
    }

    test("RecoveryAnnotation entries never contribute to the balance") {
        checkAll(Arb.list(cashEntry, 0..30)) { entries ->
            val withoutAnnotations = entries.filter { it.source !is EntrySource.RecoveryAnnotation }
            Ledgers.spendableCash(entries) shouldBe Ledgers.spendableCash(withoutAnnotations)
        }
    }

    test("onHand isolates product and location") {
        val a = ProductId("A")
        val b = ProductId("B")
        val entries = listOf(
            InventoryLedgerEntry(1, 1, a, Location.MACHINE, 10, EntrySource.SimTool, false, ""),
            InventoryLedgerEntry(2, 1, a, Location.STORAGE, 5, EntrySource.SimTool, false, ""),
            InventoryLedgerEntry(3, 1, b, Location.MACHINE, 7, EntrySource.SimTool, false, ""),
            InventoryLedgerEntry(4, 1, a, Location.MACHINE, 3, EntrySource.SimTool, true, ""), // disputed
        )
        Ledgers.onHand(entries, a, Location.MACHINE) shouldBe 10
        Ledgers.onHand(entries, a, Location.STORAGE) shouldBe 5
        Ledgers.onHand(entries, b, Location.MACHINE) shouldBe 7
    }
})
