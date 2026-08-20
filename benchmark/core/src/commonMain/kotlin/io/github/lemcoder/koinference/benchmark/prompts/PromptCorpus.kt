package io.github.lemcoder.koinference.benchmark.prompts

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
