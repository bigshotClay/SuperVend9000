package dev.supervend.state

import java.sql.Connection
import java.sql.DriverManager

/**
 * A single SQLite file per run (software spec §5). Owns the connection and the migration
 * runner. The run DB is the authoritative state store and the evidence/metrics package.
 */
class RunDatabase private constructor(val connection: Connection) : AutoCloseable {

    /** Applies any migrations not yet recorded in `schema_version`, in order. */
    fun migrate(): RunDatabase {
        connection.createStatement().use { st ->
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS schema_version (version TEXT PRIMARY KEY, applied_at_ms INTEGER NOT NULL)",
            )
        }
        val applied = mutableSetOf<String>()
        connection.createStatement().use { st ->
            st.executeQuery("SELECT version FROM schema_version").use { rs ->
                while (rs.next()) applied += rs.getString(1)
            }
        }
        for (name in MIGRATIONS) {
            if (name in applied) continue
            val sql = readMigration(name)
            connection.autoCommit = false
            try {
                connection.createStatement().use { st ->
                    for (stmt in splitStatements(sql)) st.executeUpdate(stmt)
                }
                connection.prepareStatement(
                    "INSERT INTO schema_version(version, applied_at_ms) VALUES (?, ?)",
                ).use { ps ->
                    ps.setString(1, name)
                    // No wall clock in migrations beyond a bookkeeping timestamp; value is
                    // never read by gates/validators, so this does not affect determinism.
                    ps.setLong(2, 0L)
                    ps.executeUpdate()
                }
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw IllegalStateException("Migration $name failed", e)
            } finally {
                connection.autoCommit = true
            }
        }
        return this
    }

    override fun close() = connection.close()

    companion object {
        /**
         * Ordered migration list. Resource listing is unreliable from a jar, so the order is
         * explicit and code-reviewed. New migrations append here.
         */
        private val MIGRATIONS = listOf(
            "db/migration/V1__core_tables.sql",
            "db/migration/V2__llm_and_baseline.sql",
        )

        fun open(path: String): RunDatabase = migrated("jdbc:sqlite:$path")

        /** In-memory DB for tests. */
        fun inMemory(): RunDatabase = migrated("jdbc:sqlite::memory:")

        private fun migrated(url: String): RunDatabase {
            val conn = DriverManager.getConnection(url)
            conn.createStatement().use { it.executeUpdate("PRAGMA foreign_keys = ON") }
            return RunDatabase(conn).migrate()
        }

        private fun readMigration(name: String): String =
            RunDatabase::class.java.classLoader.getResourceAsStream(name)?.use {
                it.readBytes().decodeToString()
            } ?: error("Migration resource not found: $name")

        /**
         * Split a SQL script into individual statements. The sqlite-jdbc driver runs one
         * statement per call. Semicolons inside a trigger's BEGIN…END body must NOT split,
         * so we track BEGIN/END nesting and only break on a top-level `;`. Line comments are
         * stripped first (none of ours contain semicolons, but this keeps the splitter safe).
         */
        internal fun splitStatements(script: String): List<String> {
            val noComments = script.lineSequence()
                .map { line -> line.substringBefore("--").trimEnd() }
                .joinToString("\n")

            val statements = mutableListOf<String>()
            val current = StringBuilder()
            val word = StringBuilder()
            var depth = 0

            fun flushWord() {
                if (word.isEmpty()) return
                when (word.toString().uppercase()) {
                    "BEGIN" -> depth++
                    "END" -> if (depth > 0) depth--
                }
                word.setLength(0)
            }

            for (ch in noComments) {
                if (ch.isLetter()) {
                    word.append(ch)
                    current.append(ch)
                    continue
                }
                flushWord()
                if (ch == ';' && depth == 0) {
                    val stmt = current.toString().trim()
                    if (stmt.isNotEmpty()) statements += stmt
                    current.setLength(0)
                } else {
                    current.append(ch)
                }
            }
            flushWord()
            val tail = current.toString().trim()
            if (tail.isNotEmpty()) statements += tail
            return statements
        }
    }
}
