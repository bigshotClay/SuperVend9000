package dev.supervend.state

import dev.supervend.model.Cents
import dev.supervend.model.EntrySource
import dev.supervend.model.IncidentId
import dev.supervend.model.Location
import dev.supervend.model.ProductId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import java.sql.SQLException

class LedgerStoreTest : FunSpec({

    fun freshStore(): Pair<RunDatabase, LedgerStore> {
        val db = RunDatabase.inMemory()
        return db to LedgerStore(db.connection)
    }

    test("migrations create the spec §5.2 tables") {
        RunDatabase.inMemory().use { db ->
            val names = mutableListOf<String>()
            db.connection.createStatement().use { st ->
                st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rs ->
                    while (rs.next()) names += rs.getString(1)
                }
            }
            names shouldContainAll listOf(
                "cash_ledger", "inventory_ledger", "cash_dispute", "inventory_dispute",
                "orders", "suppliers", "quotes", "price_book", "tasks", "incidents",
                "artifacts", "gate_log", "llm_calls", "plan_versions", "run_manifest",
            )
        }
    }

    test("cash projection equals fold of appended entries") {
        val (db, store) = freshStore()
        db.use {
            store.appendCash(1, Cents(50_000), LedgerSource.SIM_TOOL, "start")
            store.appendCash(1, Cents(-2_00), LedgerSource.SIM_TOOL, "fee")
            store.appendCash(2, Cents(1_500), LedgerSource.SIM_TOOL, "sales")
            store.spendableCash() shouldBe Cents(50_000 - 200 + 1_500)
        }
    }

    test("ledger journals are append-only: UPDATE and DELETE are rejected") {
        val (db, store) = freshStore()
        db.use {
            store.appendCash(1, Cents(1_000), LedgerSource.SIM_TOOL, "x")
            shouldThrow<SQLException> {
                db.connection.createStatement().use { it.executeUpdate("UPDATE cash_ledger SET amount_cents = 0") }
            }
            shouldThrow<SQLException> {
                db.connection.createStatement().use { it.executeUpdate("DELETE FROM cash_ledger") }
            }
            // Balance untouched by the rejected mutations.
            store.spendableCash() shouldBe Cents(1_000)
        }
    }

    test("disputed entries are excluded from spendable balance") {
        val (db, store) = freshStore()
        db.use {
            val seq = store.appendCash(1, Cents(9_999), LedgerSource.SIM_TOOL, "suspect")
            store.appendCash(1, Cents(1), LedgerSource.SIM_TOOL, "ok")
            store.spendableCash() shouldBe Cents(10_000)

            val recovery: RecoveryStateWriter = store // recovery only ever sees this interface
            recovery.disputeCash(seq, IncidentId("INC-1"), day = 2, note = "cannot reconcile")

            store.spendableCash() shouldBe Cents(1)
        }
    }

    test("recovery annotations add information but never move the balance") {
        val (db, store) = freshStore()
        db.use {
            store.appendCash(1, Cents(5_000), LedgerSource.SIM_TOOL, "seed")
            val recovery: RecoveryStateWriter = store
            recovery.annotateCash(day = 2, incident = IncidentId("INC-2"), note = "looks off but not disputing")

            store.spendableCash() shouldBe Cents(5_000)
            // The annotation row exists in the journal as evidence.
            val annotations = store.cashEntries().filter { it.source is EntrySource.RecoveryAnnotation }
            annotations.size shouldBe 1
            annotations.single().amount shouldBe Cents.ZERO
        }
    }

    test("a nonzero-amount recovery annotation row is impossible (DB CHECK)") {
        RunDatabase.inMemory().use { db ->
            shouldThrow<SQLException> {
                db.connection.createStatement().use {
                    it.executeUpdate(
                        "INSERT INTO cash_ledger(day, amount_cents, source_kind, source_incident, source_note) " +
                            "VALUES (1, 500, 'RECOVERY_ANNOTATION', 'INC', 'note')",
                    )
                }
            }
        }
    }

    test("inventory onHand projects per product and location, disputes excluded") {
        val (db, store) = freshStore()
        db.use {
            val p = ProductId("COLA")
            store.appendInventory(1, p, Location.STORAGE, 24, LedgerSource.SIM_TOOL, "delivery")
            val moveOut = store.appendInventory(1, p, Location.STORAGE, -6, LedgerSource.SIM_TOOL, "restock move")
            store.appendInventory(1, p, Location.MACHINE, 6, LedgerSource.SIM_TOOL, "restock move")

            store.onHand(p, Location.STORAGE) shouldBe 18
            store.onHand(p, Location.MACHINE) shouldBe 6

            val recovery: RecoveryStateWriter = store
            recovery.disputeInventory(moveOut, IncidentId("INC-3"), day = 2, note = "phantom move")
            store.onHand(p, Location.STORAGE) shouldBe 24
        }
    }

    test("splitStatements preserves trigger BEGIN...END bodies") {
        val sql = """
            CREATE TABLE t (a INT);
            CREATE TRIGGER t_no_update BEFORE UPDATE ON t
                BEGIN SELECT RAISE(ABORT, 'no'); END;
            CREATE INDEX idx ON t(a);
        """.trimIndent()
        val stmts = RunDatabase.splitStatements(sql)
        stmts.size shouldBe 3
        stmts[1].contains("BEGIN").shouldBeTrue()
        stmts[1].contains("END").shouldBeTrue()
    }
})
