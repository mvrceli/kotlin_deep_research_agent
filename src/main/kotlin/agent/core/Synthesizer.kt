package agent.core

class Synthesizer {
    private val openai = OpenAIClient()

    suspend fun combine(findings: List<Finding>, topic: String): String {
        // Build a content block that includes citation and a simple reliability score
        val content = findings.joinToString("\n\n") { f ->
            "Source: ${f.source} (score=${"%.2f".format(f.score)})\n${f.content}"
        }

        return openai.ask("""
            Using the following research findings:
            $content

            Write a structured, evidence-based report on "$topic".
            Include explicit citations (URL and score) for key claims. Focus on accuracy and label uncertain statements.
            Include:
            - Introduction
            - Key Findings
            - Contrasting Perspectives
            - Conclusion
        """.trimIndent())
    }
}