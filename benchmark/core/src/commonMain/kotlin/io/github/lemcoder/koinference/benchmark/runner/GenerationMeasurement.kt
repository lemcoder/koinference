package io.github.lemcoder.koinference.benchmark.runner

import io.github.lemcoder.koinference.benchmark.platform.PlatformProbe
import io.github.lemcoder.koinference.runtime.ResponsePart
import kotlinx.coroutines.flow.Flow

/**
 * The only place in the harness that measures anything.
 *
 * Every engine goes through this function, so time to first token means the same thing in every
 * record: the interval from asking for a generation to the first chunk being handed to the
 * harness. That includes the binding — JNI, cinterop, whatever the adapter is made of — which is
 * exactly right, because a caller cannot use a token that has not reached them.
 *
 * The clock is [PlatformProbe.monotonicNanos], which is `SystemClock.elapsedRealtimeNanos()` on
 * Android: monotonic, unaffected by wall-clock changes, and unaffected by the process being
 * descheduled.
 *
 * What it measures is arrival, not content: every part counts as a chunk, whatever it carries.
 * Only [ResponsePart.Text] contributes to the reply text that gets tokenized.
 *
 * What this cannot do is count tokens. A chunk is an emission, not a token — one token for
 * llama.cpp, whatever LiteRT-LM chooses to send for that engine — so [GenerationMeasurement]
 * reports chunks under that name and leaves tokens to whoever has a tokenizer. Calling chunks
 * tokens is how a benchmark reports a throughput number that means two different things in two
 * rows of the same table.
 */
suspend fun measureGeneration(
    probe: PlatformProbe,
    chunks: Flow<ResponsePart>,
    /**
     * Counts tokens in the finished reply with the engine's own tokenizer, or null when the
     * engine has none.
     *
     * Called after the clock stops, so tokenizing never lands inside a timing. The harness does
     * the counting for both engines rather than asking each for a number, which is the same rule
     * the timings follow: one code path, one definition.
     */
    countTokens: (suspend (String) -> Int)? = null,
): GenerationMeasurement {
    val startNanos = probe.monotonicNanos()
    var firstChunkNanos: Long? = null
    var chunkCount = 0
    val collected = StringBuilder()

    chunks.collect { chunk ->
        // Stamped before anything is done with the chunk, including appending it: the
        // measurement is when it arrived, not when the harness finished handling it.
        if (firstChunkNanos == null) firstChunkNanos = probe.monotonicNanos()
        chunkCount++
        // Only text is collected, and only text is tokenized. A reply can carry audio or image
        // parts, which are emissions like any other — so they count as chunks and move the clock
        // — but they have no characters and no token count, and folding a byte array into the
        // reply text would put a number in the tokens column that means nothing.
        if (chunk is ResponsePart.Text) collected.append(chunk.text)
    }

    val endNanos = probe.monotonicNanos()
    val text = collected.toString()
    val tokens = countTokens?.invoke(text)?.takeIf { it >= 0 }

    return GenerationMeasurement(
        text = text,
        totalMs = (endNanos - startNanos) / NANOS_PER_MILLI,
        // Null, not zero, when the engine produced nothing at all: no first chunk ever arrived,
        // so there is no first-token time to report.
        timeToFirstChunkMs = firstChunkNanos?.let { (it - startNanos) / NANOS_PER_MILLI },
        // From the first chunk, so a long prompt does not depress the rate.
        streamingMs = firstChunkNanos?.let { (endNanos - it) / NANOS_PER_MILLI },
        chunks = chunkCount,
        generatedTokens = tokens,
    )
}

/**
 * One generation, as measured from outside the engine.
 *
 * @property chunks emissions, not tokens. See [measureGeneration].
 * @property chunksPerSecond over [streamingMs], counting from the first chunk. Comparable
 *           between engines only to the extent their chunks are comparable — which is why the
 *           analysis layer reports the chunk count next to it.
 */
data class GenerationMeasurement(
    val text: String,
    val totalMs: Double,
    val timeToFirstChunkMs: Double?,
    val streamingMs: Double?,
    val chunks: Int,
    /** Tokens in [text] by the engine's own tokenizer, or null when it exposes none. */
    val generatedTokens: Int? = null,
) {

    /**
     * Generated tokens per second over the streaming interval.
     *
     * The number a reader actually wants, and comparable between engines because both counts
     * come from this harness calling each model's tokenizer — not from an engine's own report.
     */
    val tokensPerSecond: Double?
        get() = if (generatedTokens != null && streamingMs != null && streamingMs > 0.0) {
            generatedTokens * 1000.0 / streamingMs
        } else {
            null
        }

    val chunksPerSecond: Double?
        get() = if (streamingMs != null && streamingMs > 0.0 && chunks > 1) {
            // chunks - 1: the first one arrived before this interval started.
            (chunks - 1) * 1000.0 / streamingMs
        } else {
            null
        }
}
