package io.github.lemcoder.koinference.benchmark

import io.github.lemcoder.koinference.GenerationTelemetry

/**
 * An engine the harness can benchmark.
 *
 * The harness knows nothing about llama.cpp or LiteRT-LM past this interface — adding a third
 * engine means writing one adapter, not touching the protocol, the schema or the runner.
 *
 * Adapters live in this module rather than in the backends: benchmarking is not a backend's
 * job, and a backend that grew a benchmark API would have to keep it stable for everyone.
 */
interface BenchmarkInferenceEngine {

    /** Stable identifier, recorded in results. Two runs with the same id ran the same adapter. */
    val id: String

    /**
     * Everything about this engine's configuration that a reader would need to reproduce the
     * run, as flat key/value pairs — thread count, backend, context size, delegate.
     *
     * Kept out of the common schema on purpose: these differ per engine and would otherwise
     * turn into a union of every engine's settings, most of them null. A setting the adapter
     * cannot determine is omitted rather than guessed.
     */
    fun metadata(config: BenchmarkModelConfig): Map<String, String>

    /**
     * Load the model and prepare for generation.
     *
     * Timing this is the caller's job — [BenchmarkRunner] measures it as model-load time — so
     * an adapter must not do lazy work here that will land in the first generation instead.
     */
    suspend fun initialize(config: BenchmarkModelConfig): EngineSession

    interface EngineSession {

        /**
         * Run one generation.
         *
         * Must not retry, warm up, or cache across calls beyond what the engine does by
         * itself: the runner decides what is a warmup and what is a measurement.
         */
        suspend fun generate(request: GenerationRequest): GenerationResult

        suspend fun close()
    }
}

/**
 * One generation to perform.
 *
 * Identical for every engine, which is what makes results comparable at all. An engine that
 * cannot honour a field must say so in [GenerationResult.notes] rather than substituting its
 * own value.
 */
data class GenerationRequest(
    val promptId: String,
    val prompt: String,
    val maxNewTokens: Int,
)

/**
 * The outcome of one generation.
 *
 * @property text what the model produced. Kept so a reader can tell a degenerate run (empty,
 *           or a single repeated token) from a fast one.
 * @property wallClockMs measured by the harness around the whole call, always available.
 *           Not a substitute for [telemetry]: it includes the adapter and the binding.
 * @property telemetry what the engine measured about itself, or null when it cannot.
 * @property notes anything the adapter had to compromise on, recorded rather than hidden.
 */
data class GenerationResult(
    val text: String,
    val wallClockMs: Double,
    val telemetry: GenerationTelemetry?,
    val notes: List<String> = emptyList(),
)
