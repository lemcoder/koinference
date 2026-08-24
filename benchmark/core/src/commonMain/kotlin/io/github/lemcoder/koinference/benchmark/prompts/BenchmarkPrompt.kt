package io.github.lemcoder.koinference.benchmark.prompts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
