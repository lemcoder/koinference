package io.github.lemcoder.koinference.benchmark.result

import kotlinx.serialization.Serializable

/**
 * One iteration.
 *
 * Everything here was measured by the harness, above the engine, with one clock — see
 * [measureGeneration]. No field comes from an engine describing itself.
 */
@Serializable
data class GenerationSample(
    val iteration: Int,
    /** Ask to last chunk, measured by the harness. */
    val wallClockMs: Double,
    /**
     * Ask to *first* chunk. Null only when the engine produced nothing at all.
     *
     * Measured above the engine, identically for every engine, so this number means the same
     * thing in every row. It includes the binding the chunk travelled through, which is what a
     * caller actually waits for.
     */
    val ttftMs: Double? = null,
    /** First chunk to last, so throughput does not vary with prompt length. */
    val streamingMs: Double? = null,
    /**
     * Emissions, not tokens — one token per chunk for llama.cpp, whatever LiteRT-LM sends for
     * LiteRT-LM. Named for what it is so nobody divides it into a token throughput.
     */
    val chunks: Int = 0,
    val chunksPerSecond: Double? = null,
    /**
     * Tokens in the reply, counted by the harness with the engine's own tokenizer.
     *
     * Null when a backend exposes no tokenizer. Never derived from chunks or characters.
     */
    val generatedTokens: Int? = null,
    val tokensPerSecond: Double? = null,
    /** Characters produced. Not a token count, and never used as one. */
    val outputChars: Int,
    val peakPssKb: Long? = null,
)
