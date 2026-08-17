package io.github.lemcoder.koinference

/**
 * What a backend measured about its own last generation.
 *
 * Every field is nullable and null means *not measured*, never zero. A benchmark that cannot
 * tell those apart reports a fabricated number.
 *
 * These come from inside the engine, not from a caller with a stopwatch — [timeToFirstTokenMs]
 * in particular cannot be recovered afterwards by dividing a total duration by a token count.
 * llama.cpp records them in its decode loop through the facade's `koi_last_*` getters;
 * LiteRT-LM reports its own (`BenchmarkInfo` on Android, `litert_lm_session_get_benchmark_info`
 * on Apple), which is why the two are comparable at all.
 *
 * @property prefillMs time spent turning the prompt into KV-cache state.
 * @property decodeMs time from the first generated token to the last, so throughput computed
 *           from it does not vary with prompt length.
 * @property prefillTokensPerSecond as reported by the engine, or derived from
 *           [promptTokens] and [prefillMs] when it reports counts and durations instead.
 * @property engineInitMs engine-reported initialisation time; only LiteRT-LM has it.
 */
enum class TelemetrySource {

    /** The engine measured it internally and reported it. */
    ENGINE,

    /**
     * The first streamed chunk was timestamped as it arrived at the binding.
     *
     * A real first-token measurement, but taken one layer out from [ENGINE] — it includes
     * whatever the binding does between the engine emitting and the callback running. Never
     * average the two sources together.
     */
    STREAM_FIRST_CHUNK,
}

data class GenerationTelemetry(
    /** Where these numbers came from. Recorded per sample so a comparison can be audited. */
    val source: TelemetrySource,
    val timeToFirstTokenMs: Double? = null,
    val prefillMs: Double? = null,
    val decodeMs: Double? = null,
    val promptTokens: Int? = null,
    val decodeTokens: Int? = null,
    val prefillTokensPerSecond: Double? = null,
    val decodeTokensPerSecond: Double? = null,
    val engineInitMs: Double? = null,
)

/**
 * A runtime that reports [GenerationTelemetry] for its last generation.
 *
 * Separate from [TextRuntime] because a backend can generate perfectly well without being able
 * to say anything about how; implementing this is a claim that the numbers are real.
 */
interface InstrumentedRuntime {

    /** Telemetry of the most recent generation, or null if none has completed. */
    val lastGeneration: GenerationTelemetry?
}
