package dev.supervend.app

import dev.supervend.llm.qualify.CaseResult
import dev.supervend.llm.qualify.QualificationReport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.sql.Connection

/** Persists a [QualificationReport] to the run DB's `qualification` table (software spec §11). */
object QualificationStore {
    fun save(conn: Connection, report: QualificationReport) {
        conn.prepareStatement(
            "INSERT INTO qualification(model_id, battery_hash, total, passed, score, threshold, qualified, cases_json) " +
                "VALUES (?,?,?,?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, report.modelId)
            ps.setString(2, report.batteryHash.hex)
            ps.setInt(3, report.total)
            ps.setInt(4, report.passed)
            ps.setDouble(5, report.score)
            ps.setDouble(6, report.threshold)
            ps.setInt(7, if (report.qualified) 1 else 0)
            ps.setString(8, Json.encodeToString(ListSerializer(CaseResult.serializer()), report.cases))
            ps.executeUpdate()
        }
    }
}
