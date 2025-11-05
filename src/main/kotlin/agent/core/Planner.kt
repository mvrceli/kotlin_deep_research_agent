package agent.core

class Planner {
    private val openai = OpenAIClient()

    suspend fun decompose(query: String): List<String> {
        val plan = openai.ask("""
            Decompose the research topic into 3–5 focused sub-questions.
            Topic: "$query"
            Respond as a numbered list only.
        """.trimIndent())
        return plan.lines().mapNotNull { it.substringAfter(". ").ifBlank { null } }
    }
}