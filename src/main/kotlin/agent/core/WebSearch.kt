package agent.core

import org.jsoup.Jsoup

class WebSearch {
    fun search(query: String): List<String> {
        // Basic example using DuckDuckGo HTML scraping
        val url = "https://duckduckgo.com/html/?q=${query.replace(" ", "+")}"
        val doc = Jsoup.connect(url).get()
        return doc.select("a.result__a").map { it.attr("href") }.take(3)
    }
}