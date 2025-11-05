package agent.core

import java.time.Instant

data class Finding(
    val id: Long? = null,
    val query: String,
    val source: String,
    val content: String,
    val score: Double,
    val timestamp: String = Instant.now().toString()
)
