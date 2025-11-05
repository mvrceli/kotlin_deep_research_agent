Deep Research Agent (Kotlin)
=============================

This is a small Kotlin JVM CLI that performs an automated, iterative research workflow:
- decomposes a high-level research topic into sub-questions
- searches the web for relevant pages
- fetches and (if necessary) extracts text from HTML and PDF sources
- summarizes long documents using chunked summarization to avoid model context limits
- stores findings in a local SQLite memory store with simple reliability scoring
- synthesizes a final, evidence-based report including source citations

Prerequisites
-------------
- JDK 17 or higher (Temurin, AdoptOpenJDK, etc.)
- Gradle wrapper is included. If you prefer a system Gradle, ensure it's compatible with the Kotlin plugin.

Key files
---------
- `build.gradle.kts` — project build; dependencies include Ktor, kotlinx-serialization, Jsoup, PDFBox, sqlite-jdbc, and a lightweight SLF4J binding.
- `src/main/kotlin/agent/Main.kt` — program entrypoint and CLI glue.
- `src/main/kotlin/agent/core/Planner.kt` — decomposes the main topic into sub-questions using the OpenAI client.
- `src/main/kotlin/agent/core/WebSearch.kt` — (simple) web search helper used by the agent (you may replace this with a real search connector).
- `src/main/kotlin/agent/core/OpenAIClient.kt` — wraps OpenAI calls, handles retries, chunked summarization, HTML/PDF extraction, and error handling.
- `src/main/kotlin/agent/core/DeepResearchAgent.kt` — orchestrates planning, retrieval, summarization, memory storage, recursive follow-ups, and synthesis.
- `src/main/kotlin/agent/core/Memory.kt` — simple SQLite helper that stores `findings` in `research_memory.db`.
- `src/main/kotlin/agent/core/Finding.kt` — data model for findings saved to memory.
- `src/main/kotlin/agent/core/Synthesizer.kt` — combines findings into the final report (includes citations and scores).
- `src/main/kotlin/agent/core/Utils.kt` — small helpers, including a console progress bar.

How it works (high level)
-------------------------
1. You run the program with a short topic or a URL. The CLI supports:
	- Passing a topic as a command-line argument
	- Piping text into the process
	- Interactive prompt if you run the program without input
2. The `Planner` asks the model to decompose the topic into 3–5 sub-questions.
3. For each sub-question the `WebSearch` returns a list of candidate URLs.
4. Each URL is fetched:
	- HTML pages are parsed with Jsoup.
	- PDFs are fetched as bytes and text is extracted with PDFBox.
	- If pages are extremely long, the client splits them into overlapping chunks and summarizes each chunk, then combines chunk summaries into a final document summary.
5. Each summarized finding is scored by a small heuristic and persisted to the local SQLite DB (`research_memory.db`).
6. The `Synthesizer` asks the model to write a structured report using the collected findings, including source URLs and scores to help the model produce citations and trust labels.
7. The agent will optionally ask the model whether to dive deeper (recursive reasoning). Follow-ups are limited to prevent runaway loops.

Environment variables and runtime flags
-------------------------------------
- `OPENAI_API_KEY` (required) — your OpenAI key. Prefer exporting it in your shell or using a secret manager. Do NOT commit keys to source control.
- `OPENAI_MODEL` (optional) — override the default model (default is `gpt-3.5-turbo`).
- `MAX_SUBTASKS` (optional) — cap the number of sub-questions the planner will return (default: 5).
- `MAX_PAGES_PER_TASK` (optional) — cap pages fetched per sub-question (default: 5).
- `MAX_CHUNKS_PER_DOC` (optional) — cap the number of chunks processed per document to limit cost (default: 12).

Running the project
-------------------
Recommended (interactive, zsh-friendly): prompts for your key securely, then runs the program with a topic argument.
```bash
read -s "OPENAI_API_KEY?Enter OpenAI API key: "
export OPENAI_API_KEY=$OPENAI_API_KEY
export MAX_SUBTASKS=3
export MAX_PAGES_PER_TASK=2
export MAX_CHUNKS_PER_DOC=6
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew run --args='your research topic here'
```

Notes on cost and runtime
------------------------
- Chunked summarization issues multiple OpenAI calls: expect higher token usage for long documents. Tune `MAX_CHUNKS_PER_DOC` and chunk sizes in `OpenAIClient.kt` to balance cost vs. fidelity.
- Progress is shown in the terminal for page fetching and chunk summarization — useful for long runs.
- You can reduce runtime by lowering `MAX_SUBTASKS`, `MAX_PAGES_PER_TASK`, and `MAX_CHUNKS_PER_DOC`.

Security and best practices
---------------------------
- Rotate your API key if it has been exposed.
- For production, move secrets to a dedicated secret manager and consider rate-limiting and retry strategies.

Extending the project
---------------------
- Replace the `WebSearch` implementation with a proper search API (Google, Bing, SerpAPI).
- Add embeddings + a vector DB for semantic memory (SQLite + pgvector, FAISS, or a managed vector DB) to enable similarity search and deduplication.
- Add OCR (Tesseract) for scanned PDFs.
- Add a CLI helper script (`run-research.sh`) to standardize env and run options.




