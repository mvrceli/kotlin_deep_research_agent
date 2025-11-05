package agent.core

class DeepResearchAgent {
    private val planner = Planner()
    private val retriever = WebSearch()
    private val synthesizer = Synthesizer()
    private val openai = OpenAIClient()
    private val memory = Memory()

    // A simple heuristic to score source reliability.
    private fun scoreSource(url: String, contentLength: Int): Double {
        val host = try { java.net.URI(url).host.lowercase() } catch (_: Exception) { "" }
        val authoritative = listOf("nature.com", "science.org", "sciencedirect.com", "arxiv.org", "ieeexplore.ieee.org")
        val researchRepos = listOf("researchgate.net", "academia.edu")

        var score = when {
            authoritative.any { host.contains(it) } -> 0.9
            researchRepos.any { host.contains(it) } -> 0.6
            host.endsWith("edu") -> 0.85
            else -> 0.5
        }

        // Slight boost for longer content (more to work with) but cap it
        val lenBoost = (contentLength / 10_000.0).coerceAtMost(0.15)
        score = (score + lenBoost).coerceIn(0.0, 1.0)
        return score
    }

    suspend fun run(query: String): String {
        val maxSubtasks = System.getenv("MAX_SUBTASKS")?.toIntOrNull() ?: 5
        val maxPagesPerTask = System.getenv("MAX_PAGES_PER_TASK")?.toIntOrNull() ?: 5
        val subTasks = planner.decompose(query).take(maxSubtasks)
        val findings = mutableListOf<Finding>()

        // gather initial findings
        for (task in subTasks) {
            val pages = retriever.search(task).take(maxPagesPerTask)
            if (pages.size < 1) continue
            for ((i, page) in pages.withIndex()) {
                renderProgressBar(i + 1, pages.size, "Fetching pages for: $task")
                val content = openai.summarize(page)
                val score = scoreSource(page, content.length)
                val f = Finding(query = task, source = page, content = content, score = score)
                val id = memory.saveFinding(f)
                findings += f.copy(id = if (id >= 0) id else null)
            }
        }

        // synthesize a report
        var report = synthesizer.combine(findings, query)

        // simple recursive reasoning: ask the model if we should dive deeper.
        // limit depth to avoid runaway loops.
        var depth = 0
        val maxDepth = 2
        while (depth < maxDepth) {
            val followup = openai.ask(
                "Given this report:\n$report\n\nList any sub-topics or questions that need deeper research (numbered), or reply 'NO' if none."
            )
            if (followup.trim().equals("NO", ignoreCase = true) || followup.isBlank()) break

            val nextTasks = followup.lines().mapNotNull { it.substringAfter(". ").ifBlank { null } }
            val cappedNextTasks = nextTasks.take(maxSubtasks)
            if (cappedNextTasks.isEmpty()) break

            var newFindingsAdded = false
            for (nt in cappedNextTasks) {
                val pages = retriever.search(nt).take(maxPagesPerTask)
                for ((i, p) in pages.withIndex()) {
                    renderProgressBar(i + 1, pages.size, "Fetching pages for follow-up: $nt")
                    val content = openai.summarize(p)
                    val score = scoreSource(p, content.length)
                    val f = Finding(query = nt, source = p, content = content, score = score)
                    val id = memory.saveFinding(f)
                    findings += f.copy(id = if (id >= 0) id else null)
                    newFindingsAdded = true
                }
            }

            if (!newFindingsAdded) break
            report = synthesizer.combine(findings, query)
            depth += 1
        }

        memory.close()
        return report
    }
}