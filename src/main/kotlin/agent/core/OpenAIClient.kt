package agent.core

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.call.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.UnsupportedMimeTypeException
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.delay
import kotlin.random.Random

class OpenAIClient {
    private val apiKey = System.getenv("OPENAI_API_KEY")
        ?: error("Please set the OPENAI_API_KEY environment variable.")

    // share a Json instance configured to ignore unknown keys so we can safely
    // deserialize API responses that may include extra fields (like error wrappers)
    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    init {
        // Reduce PDFBox/fontbox logging noise which can be very verbose.
        try {
            Logger.getLogger("org.apache.pdfbox").level = Level.WARNING
            Logger.getLogger("org.apache.fontbox").level = Level.WARNING
        } catch (_: Throwable) {
            // ignore if logging can't be configured
        }
    }

    @Serializable
    private data class Message(val role: String, val content: String)

    // The model to use can be overridden with OPENAI_MODEL env var; default to a
    // broadly-available model so API keys without access to preview models still work.
    private val model: String = System.getenv("OPENAI_MODEL") ?: "gpt-3.5-turbo"

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<Message>
    )

    @Serializable
    private data class ChatResponse(
        val choices: List<Choice>
    ) {
        @Serializable
        data class Choice(
            val message: Message
        )
    }

    suspend fun ask(prompt: String): String {
        // Implement retry/backoff for transient server errors (5xx) and OpenAI server_error
        val maxAttempts = 3
        var lastError: Throwable? = null
        for (attempt in 1..maxAttempts) {
            try {
                val response = client.post("https://api.openai.com/v1/chat/completions") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $apiKey")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(ChatRequest(model = model, messages = listOf(
                        Message("system", "You are a helpful research assistant."),
                        Message("user", prompt)
                    )))
                }

                val raw = try { response.bodyAsText() } catch (e: Exception) {
                    throw IllegalStateException("Failed to read OpenAI response body: ${e.message}", e)
                }

                // If HTTP indicates server error, retry
                if (response.status.value >= 500) {
                    lastError = IllegalStateException("OpenAI HTTP ${response.status.value}: server error")
                    // backoff before retrying
                    if (attempt < maxAttempts) {
                        val backoff = (1000L * (1 shl (attempt - 1))) + Random.nextLong(0, 500)
                        delay(backoff)
                        continue
                    } else break
                }

                // Try to parse successful response
                try {
                    val chat = json.decodeFromString(ChatResponse.serializer(), raw)
                    return chat.choices.firstOrNull()?.message?.content ?: "No response."
                } catch (e: kotlinx.serialization.SerializationException) {
                    // Parse the raw body for an error object
                    try {
                        val parsed = json.parseToJsonElement(raw)
                        val errObj = parsed.jsonObject["error"]
                        val errMsg = errObj?.jsonObject?.get("message")?.jsonPrimitive?.content
                        val errType = try { errObj?.jsonObject?.get("type")?.jsonPrimitive?.content } catch (_: Exception) { null }

                        if (!errMsg.isNullOrBlank()) {
                            // If OpenAI reports a transient server error, retry
                            if (errType != null && errType.contains("server", ignoreCase = true) && attempt < maxAttempts) {
                                val backoff = (1000L * (1 shl (attempt - 1))) + Random.nextLong(0, 500)
                                delay(backoff)
                                continue
                            }

                            // Provide a helpful message for context length errors
                            if (errMsg.contains("context length", ignoreCase = true) ||
                                errMsg.contains("context_length_exceeded", ignoreCase = true)
                            ) {
                                throw IllegalStateException(
                                    "OpenAI API error: $errMsg. Suggestion: shorten your prompt or switch to a larger-context model (set OPENAI_MODEL). " +
                                        "If you're summarizing a long URL, consider chunking the content or truncating the article before sending."
                                )
                            }

                            throw IllegalStateException("OpenAI API error: $errMsg")
                        }
                    } catch (_: Exception) {
                        // fall through to throw generic parsing issue below
                    }

                    lastError = IllegalStateException("Failed to parse OpenAI response as ChatResponse. Raw body: $raw", e)
                }
            } catch (e: Exception) {
                // network or unexpected exception; may be transient
                lastError = e
                if (attempt < maxAttempts) {
                    val backoff = (1000L * (1 shl (attempt - 1))) + Random.nextLong(0, 500)
                    delay(backoff)
                    continue
                }
            }
        }

        throw IllegalStateException("OpenAI request failed after $maxAttempts attempts", lastError)
    }

    suspend fun summarize(url: String): String {
        // Try a browser-like request first. Some sites (ResearchGate, publishers)
        // return 403 to non-browser clients. We set a modern User-Agent and
        // referrer and increase the timeout. If Jsoup fails with a status error,
        // fall back to fetching via the Ktor client and parse the returned HTML.
        val userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val referrer = "https://www.google.com"

        val text = try {
            Jsoup.connect(url)
                .userAgent(userAgent)
                .referrer(referrer)
                .timeout(15_000)
                .ignoreHttpErrors(true)
                .get()
                .text()
        } catch (e: org.jsoup.HttpStatusException) {
            // Status failures (403/429/etc) — attempt a direct HTTP fetch with
            // a browser-like header set using our Ktor client and parse that.
            try {
                val body = client.get(url) {
                    headers {
                        append(HttpHeaders.UserAgent, userAgent)
                        append(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
                        append(HttpHeaders.Referrer, referrer)
                        append(HttpHeaders.Accept, "text/html,application/xhtml+xml")
                    }
                }.bodyAsText()
                org.jsoup.Jsoup.parse(body).text()
            } catch (ex: Exception) {
                throw IllegalStateException(
                    "Failed to fetch URL (status ${e.statusCode}): ${e.message}; fallback fetch failed: ${ex.message}"
                )
            }
        } catch (e: UnsupportedMimeTypeException) {
            // The resource is not an HTML document (for example, a PDF).
            try {
                val response = client.get(url) {
                    headers {
                        append(HttpHeaders.UserAgent, userAgent)
                        append(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
                        append(HttpHeaders.Referrer, referrer)
                        append(HttpHeaders.Accept, "*/*")
                    }
                }

                val contentType = response.headers[HttpHeaders.ContentType]?.lowercase() ?: ""
                if (contentType.contains("application/pdf") || e.message?.contains("pdf", ignoreCase = true) == true) {
                    // Read raw bytes and extract text with PDFBox
                    val bytes: ByteArray = response.body()
                    val pdfText = ByteArrayInputStream(bytes).use { bis ->
                        PDDocument.load(bis).use { doc ->
                            PDFTextStripper().getText(doc)
                        }
                    }
                    pdfText
                } else {
                    // Not a PDF — try to get text body and parse
                    val bodyText = response.bodyAsText()
                    org.jsoup.Jsoup.parse(bodyText).text()
                }
            } catch (ex: Exception) {
                throw IllegalStateException(
                    "Failed to fetch URL (unsupported mime): ${e.message}; fallback fetch failed: ${ex.message}"
                )
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to fetch URL: ${e.message}", e)
        }

        // If the article is very long, chunk it and summarize each chunk, then
        // combine chunk summaries. This avoids context-length issues while
        // preserving more information than a blind truncation.
        val maxChunkChars = 8_000
        return if (text.length <= maxChunkChars) {
            ask("Summarize the following article:\n$text")
        } else {
            chunkAndSummarize(text, maxChunkChars)
        }
    }

    // Split text into overlapping chunks, summarize each, then combine the
    // chunk summaries into a final summary.
    private suspend fun chunkAndSummarize(text: String, chunkSize: Int = 8000, overlap: Int = 400): String {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = (start + chunkSize).coerceAtMost(text.length)
            chunks += text.substring(start, end)
            if (end == text.length) break
            start = (end - overlap).coerceAtLeast(start + 1)
        }
        // Respect a maximum chunks-per-document limit to avoid runaway token usage.
        val maxChunksPerDoc = System.getenv("MAX_CHUNKS_PER_DOC")?.toIntOrNull() ?: 12
        if (chunks.size > maxChunksPerDoc) {
            println("Note: document split into ${chunks.size} chunks. Truncating to first $maxChunksPerDoc chunks to limit cost and time.")
            val kept = chunks.take(maxChunksPerDoc)
            chunks.clear()
            chunks += kept
        }

        val chunkSummaries = mutableListOf<String>()
        for ((i, c) in chunks.withIndex()) {
            // show progress for each chunk
            renderProgressBar(i + 1, chunks.size, "Summarizing chunks")
            val prompt = """
                Summarize the following text chunk (chunk ${i + 1} of ${chunks.size}) into a concise paragraph (3-5 sentences). If there are important claims, mark uncertainty.
                ----
                $c
                ----
            """.trimIndent()
            val s = ask(prompt)
            chunkSummaries += "Chunk ${i + 1} summary:\n$s"
        }

        // Combine chunk summaries into a cohesive final summary
        val combined = chunkSummaries.joinToString("\n\n")
        val finalPrompt = """
            Combine and refine the following chunk summaries into a single structured summary. Resolve minor contradictions and label uncertain claims. Keep it concise and evidence-focused.

            $combined
        """.trimIndent()

        return ask(finalPrompt)
    }
}