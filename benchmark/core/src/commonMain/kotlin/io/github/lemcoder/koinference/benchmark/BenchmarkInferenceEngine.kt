package io.github.lemcoder.koinference.benchmark

import kotlinx.coroutines.flow.Flow

/**
 * An engine the harness can benchmark.
 *
 * An adapter's only job is to turn a model path into something that streams chunks. It reports
 * no timings, no token counts and no throughput — everything measured comes from
 * [measureGeneration], which is one function, run identically for every engine.
 *
 * That is the whole point. An engine that timed itself would be measuring at a layer no other
 * engine measures at: llama.cpp could stamp its decode loop, LiteRT-LM's Android SDK computes
 * its own numbers, and the Apple binding computes none — three definitions of "time to first
 * token" in one results file, none comparable with another. A benchmark's value is in its
 * methodology being the same everywhere, so the measurement lives here, above all of them.
 */
interface BenchmarkInferenceEngine {

    /** Stable identifier, recorded in results. Two runs with the same id ran the same adapter. */
    val id: String

    /**
     * Everything about this engine's configuration a reader would need to reproduce the run,
     * as flat key/value pairs — thread count, backend, context size.
     *
     * Kept out of the common schema on purpose: these differ per engine and would otherwise
     * become a union of every engine's settings, most of them null. A setting the adapter
     * cannot determine is omitted rather than guessed.
     */
    fun metadata(config: BenchmarkModelConfig): Map<String, String>

    /**
     * Apply the knobs for the workload about to run, before [initialize].
     *
     * On the interface rather than resolved by type: both current engines fix their output limit
     * and their sampler when the model is loaded rather than per request, and an engine that does
     * not need either can ignore this. The alternative — the runner matching on concrete engine
     * types — meant a new backend silently ran with the wrong token budget instead of failing to
     * compile.
     */
    fun applyWorkload(workload: WorkloadConfig, sampling: SamplingConfig)

    /**
     * Load the model and prepare to generate.
     *
     * Timing this is the caller's job, so an adapter must not defer loading work into the first
     * generation, where it would be counted as inference.
     */
    suspend fun initialize(config: BenchmarkModelConfig): EngineSession

    interface EngineSession {

        /**
         * Stream one reply.
         *
         * Cold: nothing happens until collection. The adapter emits chunks as the engine
         * produces them and does nothing else — no buffering that would delay the first chunk,
         * because when that chunk arrives is the measurement.
         */
        fun stream(request: GenerationRequest): Flow<String>

        /**
         * Tokens in [text] by this engine's tokenizer, or null when it has none.
         *
         * The harness calls this itself rather than reading a count an engine reports about its
         * own generation, so that a token means the same thing in every row of the results.
         */
        suspend fun countTokens(text: String): Int?

        suspend fun close()
    }
}
