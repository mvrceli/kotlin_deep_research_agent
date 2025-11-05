package agent

import agent.core.*

suspend fun main(args: Array<String>) {
    println("🔍 Deep Research Agent\n")

    val query: String? = when {
        args.isNotEmpty() -> args.joinToString(" ")
        System.console() != null -> {
            print("Enter your research topic: ")
            readlnOrNull()
        }
        else -> {
            // Try to read piped stdin (non-interactive environments)
            val piped = System.`in`.bufferedReader().readText().trim()
            if (piped.isNotEmpty()) piped else {
                println("No input provided. Provide a topic as a command-line argument or pipe input into the process.")
                return
            }
        }
    }

    val report = DeepResearchAgent().run(query!!)
    println("\n📄 Final Report:\n")
    println(report)
}