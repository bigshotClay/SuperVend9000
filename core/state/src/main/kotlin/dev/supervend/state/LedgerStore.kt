package dev.supervend.state

import dev.supervend.model.Cents
import dev.supervend.model.CashLedgerEntry
import dev.supervend.model.EntrySource
import dev.supervend.model.IncidentId
import dev.supervend.model.InventoryLedgerEntry
import dev.supervend.model.Ledgers
import dev.supervend.model.Location
import dev.supervend.model.ProductId
import java.sql.Connection

/**
 * SQLite-backed ledger store. Implements every ledger interface but is only ever *handed*
 * to a seat through the narrowest interface that seat is entitled to (recovery → only
 * [RecoveryStateWriter]). The journal is append-only (enforced by DB triggers); disputes are
 * append-only markers.
 */
class LedgerStore(private val conn: Connection) :
    CashLedgerReader, InventoryLedgerReader, LedgerWriter, RecoveryStateWriter {

    // ---- LedgerWriter (balance-moving; bookkeeper/reconciler) ----

    override fun appendCash(day: Int, amount: Cents, source: LedgerSource, memo: String): Long {
        conn.prepareStatement(
            "INSERT INTO cash_ledger(day, amount_cents, source_kind, memo) VALUES (?,?,?,?)",
        ).use { ps ->
            ps.setInt(1, day)
            ps.setLong(2, amount.value)
            ps.setString(3, source.name)
            ps.setString(4, memo)
            ps.executeUpdate()
        }
        return lastRowId()
    }

    override fun appendInventory(
        day: Int,
        product: ProductId,
        location: Location,
        units: Int,
        source: LedgerSource,
        memo: String,
    ): Long {
        conn.prepareStatement(
            "INSERT INTO inventory_ledger(day, product_id, location, units, source_kind, memo) VALUES (?,?,?,?,?,?)",
        ).use { ps ->
            ps.setInt(1, day)
            ps.setString(2, product.value)
            ps.setString(3, location.name)
            ps.setInt(4, units)
            ps.setString(5, source.name)
            ps.setString(6, memo)
            ps.executeUpdate()
        }
        return lastRowId()
    }

    // ---- RecoveryStateWriter (additive only) ----

    override fun annotateCash(day: Int, incident: IncidentId, note: String): Long {
        conn.prepareStatement(
            "INSERT INTO cash_ledger(day, amount_cents, source_kind, source_incident, source_note, memo) " +
                "VALUES (?, 0, 'RECOVERY_ANNOTATION', ?, ?, '')",
        ).use { ps ->
            ps.setInt(1, day)
            ps.setString(2, incident.value)
            ps.setString(3, note)
            ps.executeUpdate()
        }
        return lastRowId()
    }

    override fun annotateInventory(
        day: Int,
        product: ProductId,
        location: Location,
        incident: IncidentId,
        note: String,
    ): Long {
        conn.prepareStatement(
            "INSERT INTO inventory_ledger(day, product_id, location, units, source_kind, source_incident, source_note, memo) " +
                "VALUES (?, ?, ?, 0, 'RECOVERY_ANNOTATION', ?, ?, '')",
        ).use { ps ->
            ps.setInt(1, day)
            ps.setString(2, product.value)
            ps.setString(3, location.name)
            ps.setString(4, incident.value)
            ps.setString(5, note)
            ps.executeUpdate()
        }
        return lastRowId()
    }

    override fun disputeCash(targetSeq: Long, incident: IncidentId, day: Int, note: String) {
        insertDispute("cash_dispute", targetSeq, incident, day, note)
    }

    override fun disputeInventory(targetSeq: Long, incident: IncidentId, day: Int, note: String) {
        insertDispute("inventory_dispute", targetSeq, incident, day, note)
    }

    private fun insertDispute(table: String, targetSeq: Long, incident: IncidentId, day: Int, note: String) {
        conn.prepareStatement(
            "INSERT INTO $table(target_seq, incident_id, note, day) VALUES (?,?,?,?)",
        ).use { ps ->
            ps.setLong(1, targetSeq)
            ps.setString(2, incident.value)
            ps.setString(3, note)
            ps.setInt(4, day)
            ps.executeUpdate()
        }
    }

    // ---- Readers ----

    override fun cashEntries(): List<CashLedgerEntry> {
        val sql =
            "SELECT c.seq, c.day, c.amount_cents, c.source_kind, c.source_incident, c.source_note, c.memo, " +
                "EXISTS(SELECT 1 FROM cash_dispute d WHERE d.target_seq = c.seq) AS disputed " +
                "FROM cash_ledger c ORDER BY c.seq"
        val out = mutableListOf<CashLedgerEntry>()
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    out += CashLedgerEntry(
                        seq = rs.getLong("seq"),
                        day = rs.getInt("day"),
                        amount = Cents(rs.getLong("amount_cents")),
                        source = decodeSource(
                            rs.getString("source_kind"),
                            rs.getString("source_incident"),
                            rs.getString("source_note"),
                        ),
                        disputed = rs.getInt("disputed") != 0,
                        memo = rs.getString("memo"),
                    )
                }
            }
        }
        return out
    }

    override fun inventoryEntries(): List<InventoryLedgerEntry> {
        val sql =
            "SELECT c.seq, c.day, c.product_id, c.location, c.units, c.source_kind, c.source_incident, c.source_note, c.memo, " +
                "EXISTS(SELECT 1 FROM inventory_dispute d WHERE d.target_seq = c.seq) AS disputed " +
                "FROM inventory_ledger c ORDER BY c.seq"
        val out = mutableListOf<InventoryLedgerEntry>()
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    out += InventoryLedgerEntry(
                        seq = rs.getLong("seq"),
                        day = rs.getInt("day"),
                        product = ProductId(rs.getString("product_id")),
                        location = Location.valueOf(rs.getString("location")),
                        units = rs.getInt("units"),
                        source = decodeSource(
                            rs.getString("source_kind"),
                            rs.getString("source_incident"),
                            rs.getString("source_note"),
                        ),
                        disputed = rs.getInt("disputed") != 0,
                        memo = rs.getString("memo"),
                    )
                }
            }
        }
        return out
    }

    override fun spendableCash(): Cents = Ledgers.spendableCash(cashEntries())

    override fun onHand(product: ProductId, location: Location): Int =
        Ledgers.onHand(inventoryEntries(), product, location)

    private fun decodeSource(kind: String, incident: String?, note: String?): EntrySource =
        when (kind) {
            "SIM_TOOL" -> EntrySource.SimTool
            "RECONCILIATION" -> EntrySource.Reconciliation
            "RECOVERY_ANNOTATION" -> EntrySource.RecoveryAnnotation(
                incident = IncidentId(incident ?: error("annotation row missing incident")),
                note = note ?: "",
            )
            else -> error("unknown source_kind: $kind")
        }

    private fun lastRowId(): Long =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").use { rs ->
                rs.next(); rs.getLong(1)
            }
        }
}
