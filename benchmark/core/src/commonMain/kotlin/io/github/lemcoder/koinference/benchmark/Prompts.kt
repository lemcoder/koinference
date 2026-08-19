package io.github.lemcoder.koinference.benchmark

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The prompt corpus, loaded from `benchmark/fixtures/prompts.json`.
 *
 * A fixture file rather than string literals in test code: the prompts are part of what a
 * result means, so they are versioned, checksummed, and identical for every engine. Changing
 * a prompt's text without changing its id would silently make old and new results
 * incomparable, which is what [BenchmarkPrompt.sha256] is there to catch.
 */
@Serializable
data class PromptCorpus(
    val corpusVersion: String,
    val prompts: List<BenchmarkPrompt>,
) {
    fun byId(id: String): BenchmarkPrompt =
        prompts.firstOrNull { it.id == id }
            ?: error("No prompt '$id' in corpus $corpusVersion. Known: ${prompts.map { it.id }}")

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): PromptCorpus = json.decodeFromString(serializer(), text)
    }
}

/**
 * @property id stable across corpus versions, e.g. `short_generation_v1`. A changed prompt
 *           gets a new id rather than new text under the old one.
 * @property sha256 of [text], computed when the fixture was written. The harness carries it
 *           into results instead of computing it: there is no hash in the Kotlin common
 *           standard library, and inventing one here would produce a digest nothing else
 *           could reproduce.
 * @property approxInputTokens what the corpus author expected, for sizing only. Results carry
 *           the engine's own count, never this.
 */
@Serializable
data class BenchmarkPrompt(
    val id: String,
    val category: String,
    val text: String,
    val sha256: String? = null,
    val approxInputTokens: Int? = null,
    val notes: String? = null,
)
