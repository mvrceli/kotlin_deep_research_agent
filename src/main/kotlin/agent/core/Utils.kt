package agent.core

fun extractSubQuestions(plan: String): List<String> {
    return plan.lines()
        .map { it.trim().removePrefix("-").removePrefix("*") }
        .filter { it.isNotBlank() }
}

// Render a simple text-based progress bar in-place. Example usage:
fun renderProgressBar(current: Int, total: Int, label: String = "Progress") {
    val width = 30
    val pct = if (total > 0) (current * 100) / total else 100
    val filled = (pct * width) / 100
    val bar = "#".repeat(filled) + "-".repeat((width - filled).coerceAtLeast(0))
    print("\r$label: [$bar] $pct% ($current/$total)")
    if (current >= total) println()
    System.out.flush()
}