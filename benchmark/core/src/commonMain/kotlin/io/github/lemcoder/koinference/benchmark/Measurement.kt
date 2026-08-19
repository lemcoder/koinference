package io.github.lemcoder.koinference.benchmark

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
 * What this cannot do is count tokens. A chunk is an emission, not a token — one token for
 * llama.cpp, whatever LiteRT-LM chooses to send for that engine — so [GenerationMeasurement]
 * reports chunks under that name and leaves tokens to whoever has a tokenizer. Calling chunks
 * tokens is how a benchmark reports a throughput number that means two different things in two
 * rows of the same table.
 */
suspend fun measureGeneration(probe: PlatformProbe, chunks: Flow<String>): GenerationMeasurement {
    val startNanos = probe.monotonicNanos()
    var firstChunkNanos: Long? = null
    var chunkCount = 0
    val text = StringBuilder()

    chunks.collect { chunk ->
        // Stamped before anything is done with the chunk, including appending it: the
        // measurement is when it arrived, not when the harness finished handling it.
        if (firstChunkNanos == null) firstChunkNanos = probe.monotonicNanos()
        chunkCount++
        text.append(chunk)
    }

    val endNanos = probe.monotonicNanos()
    return GenerationMeasurement(
        text = text.toString(),
        totalMs = (endNanos - startNanos) / NANOS_PER_MILLI,
        // Null, not zero, when the engine produced nothing at all: no first chunk ever arrived,
        // so there is no first-token time to report.
        timeToFirstChunkMs = firstChunkNanos?.let { (it - startNanos) / NANOS_PER_MILLI },
        // From the first chunk, so a long prompt does not depress the rate.
        streamingMs = firstChunkNanos?.let { (endNanos - it) / NANOS_PER_MILLI },
        chunks = chunkCount,
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
) {
    val chunksPerSecond: Double?
        get() = if (streamingMs != null && streamingMs > 0.0 && chunks > 1) {
            // chunks - 1: the first one arrived before this interval started.
            (chunks - 1) * 1000.0 / streamingMs
        } else {
            null
        }
}

private const val NANOS_PER_MILLI = 1_000_000.0
