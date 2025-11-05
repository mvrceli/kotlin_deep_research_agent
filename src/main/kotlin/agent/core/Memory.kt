package agent.core

import java.sql.DriverManager
import java.sql.Connection

class Memory(private val dbPath: String = "research_memory.db") {
    private val conn: Connection

    init {
        // open or create the sqlite database file in the project directory
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS findings (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  query TEXT,
                  source TEXT,
                  content TEXT,
                  score REAL,
                  timestamp TEXT
                )
                """.trimIndent()
            )
        }
    }

    fun saveFinding(finding: Finding): Long {
        val sql = "INSERT INTO findings (query, source, content, score, timestamp) VALUES (?, ?, ?, ?, ?)"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, finding.query)
            ps.setString(2, finding.source)
            ps.setString(3, finding.content)
            ps.setDouble(4, finding.score)
            ps.setString(5, finding.timestamp)
            ps.executeUpdate()
            val rs = ps.generatedKeys
            return if (rs.next()) rs.getLong(1) else -1
        }
    }

    fun fetchFindingsForQuery(query: String, limit: Int = 10): List<Finding> {
        val sql = "SELECT id, query, source, content, score, timestamp FROM findings WHERE query LIKE ? ORDER BY score DESC LIMIT ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, "%${query}%")
            ps.setInt(2, limit)
            val rs = ps.executeQuery()
            val results = mutableListOf<Finding>()
            while (rs.next()) {
                results += Finding(
                    id = rs.getLong("id"),
                    query = rs.getString("query"),
                    source = rs.getString("source"),
                    content = rs.getString("content"),
                    score = rs.getDouble("score"),
                    timestamp = rs.getString("timestamp")
                )
            }
            return results
        }
    }

    fun close() {
        conn.close()
    }
}
