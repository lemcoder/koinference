package io.github.lemcoder.koinference.benchmark

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one function every reported number comes out of, tested directly.
 *
 * It used to be reachable only through a real model on macOS arm64, which meant the rules it
 * encodes — when time to first chunk is null, what the streaming interval excludes, why
 * chunks/sec subtracts one — were asserted by nothing.
 */
class MeasurementTest {

    private fun probe(startNanos: Long = 0L) = FakePlatformProbe(nanos = startNanos)

    @Test
    fun `stamps the first chunk when it arrives rather than when the flow completes`() = runTest {
        val probe = probe()
        val chunks = flow {
            probe.advance(30_000_000L) // 30 ms of prefill
            emit("a")
            probe.advance(70_000_000L) // 70 ms of decode
            emit("b")
        }

        val measurement = measureGeneration(probe, chunks)

        assertEquals(30.0, measurement.timeToFirstChunkMs)
        assertEquals(100.0, measurement.totalMs)
        // From the first chunk, so a long prompt does not depress the decode rate.
        assertEquals(70.0, measurement.streamingMs)
    }

    @Test
    fun `concatenates chunks in order`() = runTest {
        val measurement = measureGeneration(probe(), flowOf("Hel", "lo ", "there"))

        assertEquals("Hello there", measurement.text)
        assertEquals(3, measurement.chunks)
    }

    @Test
    fun `an engine that produced nothing has no first-chunk time`() = runTest {
        val measurement = measureGeneration(probe(), flowOf())

        // Null, not zero: no first chunk ever arrived, so there is no first-token time to report.
        assertNull(measurement.timeToFirstChunkMs)
        assertNull(measurement.streamingMs)
        assertEquals(0, measurement.chunks)
        assertEquals("", measurement.text)
    }

    @Test
    fun `counts tokens after the clock has stopped`() = runTest {
        val probe = probe()
        var totalAtCount = 0L

        val measurement = measureGeneration(probe, flowOf("one ", "two")) { text ->
            // Tokenizing must never land inside a timing.
            probe.advance(500_000_000L)
            totalAtCount = probe.monotonicNanos()
            text.split(" ").count { it.isNotBlank() }
        }

        assertEquals(2, measurement.generatedTokens)
        assertTrue(totalAtCount > 0)
        assertTrue(measurement.totalMs < 500.0, "tokenizing was counted as generation time")
    }

    @Test
    fun `an engine with no tokenizer reports no token count`() = runTest {
        val measurement = measureGeneration(probe(), flowOf("one"), countTokens = null)

        assertNull(measurement.generatedTokens)
        assertNull(measurement.tokensPerSecond)
    }

    @Test
    fun `a negative count is treated as absent rather than as a number`() = runTest {
        // -1 is the harness's "this engine has no tokenizer"; zero tokens is a real outcome for a
        // model that generated nothing, and the two must not collapse into one value.
        val measurement = measureGeneration(probe(), flowOf("one")) { -1 }

        assertNull(measurement.generatedTokens)
    }

    @Test
    fun `tokens per second divides by the streaming interval`() = runTest {
        val probe = probe()
        val chunks = flow {
            probe.advance(100_000_000L) // prefill, excluded
            emit("a")
            probe.advance(1_000_000_000L) // 1 s of decode
            emit("b")
        }

        val measurement = measureGeneration(probe, chunks) { 10 }

        assertEquals(1000.0, measurement.streamingMs)
        assertEquals(10.0, measurement.tokensPerSecond)
    }

    @Test
    fun `chunks per second excludes the first chunk from the count`() = runTest {
        val probe = probe()
        val chunks = flow {
            emit("a")
            probe.advance(1_000_000_000L)
            emit("b")
            probe.advance(1_000_000_000L)
            emit("c")
        }

        val measurement = measureGeneration(probe, chunks)

        // Three chunks arrived but only two intervals elapsed: the first one arrived before the
        // streaming interval started.
        assertEquals(2000.0, measurement.streamingMs)
        assertEquals(1.0, measurement.chunksPerSecond)
    }

    @Test
    fun `a single chunk has no chunk rate to report`() = runTest {
        val probe = probe()
        val measurement = measureGeneration(probe, flow { probe.advance(1_000_000L); emit("all of it") })

        assertEquals(1, measurement.chunks)
        // One chunk is not a rate; reporting one would divide by an interval that never held any.
        assertNull(measurement.chunksPerSecond)
    }
}
