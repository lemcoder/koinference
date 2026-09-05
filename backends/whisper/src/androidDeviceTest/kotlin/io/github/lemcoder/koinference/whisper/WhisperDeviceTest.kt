package io.github.lemcoder.koinference.whisper

import io.github.lemcoder.koinference.Koinference
import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.ResponsePart
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * whisper.cpp on real hardware, through the generated JNI bridges.
 *
 * The only place the packaged `.so` is actually loaded by ART. Block bodies and `runBlocking`:
 * JUnit4 rejects a non-void test method, and real inference outruns runTest's default timeout.
 */
class WhisperDeviceTest {

    private val model = "/data/local/tmp/koinference/ggml-tiny.en.bin"
    private val audio = "/data/local/tmp/koinference/jfk.wav"

    /**
     * A minute of audio, because whisper's streaming granularity is its 30-second window.
     *
     * All the segments of a window are handed over when that window finishes decoding, so a clip
     * shorter than one window arrives as a single burst however well the facade streams — and an
     * assertion made against it would pass without meaning anything.
     */
    private val longAudio = "/data/local/tmp/koinference/jfk-long.wav"

    private fun skip(): Boolean = !File(model).isFile || !File(audio).isFile

    @Test
    fun transcribesOnDevice() {
        if (skip()) return
        runBlocking {
            val koi = Koinference(Whisper)
            try {
                val text = koi.load(model)
                    .generateResponse(listOf(PromptPart.AudioFile(audio)))
                    .filterIsInstance<ResponsePart.Text>()
                    .joinToString("") { it.text }

                println("WHISPER-DEVICE transcript: ${text.trim()}")
                // The sample is a known sentence, so this asserts the audio was understood rather
                // than merely that something came back.
                assertTrue(
                    text.contains("country", ignoreCase = true),
                    "expected the JFK line, got: '$text'",
                )
            } finally {
                koi.unloadAll()
            }
        }
    }

    @Test
    fun streamsSegmentsAndTimesThem() {
        if (skip() || !File(longAudio).isFile) return
        runBlocking {
            val koi = Koinference(Whisper)
            try {
                val started = System.nanoTime()
                var firstSegmentMs = -1.0
                val segments = mutableListOf<String>()

                // collect, not toList: toList waits for the whole flow before returning, so every
                // arrival would be stamped after the last one and "first" would equal "total" by
                // construction. That is what this test claimed the first time it was written.
                koi.load(model).streamResponse(listOf(PromptPart.AudioFile(longAudio)))
                    .collect { part ->
                        if (part is ResponsePart.Text) {
                            if (firstSegmentMs < 0) firstSegmentMs = (System.nanoTime() - started) / 1e6
                            segments += part.text
                        }
                    }

                val totalMs = (System.nanoTime() - started) / 1e6
                println(
                    "WHISPER-DEVICE ${segments.size} segments, first at ${firstSegmentMs.toInt()}ms, " +
                        "total ${totalMs.toInt()}ms: ${segments.joinToString("|") { it.trim() }}",
                )

                assertTrue(segments.size > 1, "expected several segments from a minute of audio")
                // The point of the queue in the facade: the first window reaches the caller while
                // whisper is still decoding the rest. A tenth of the total is a generous bar and
                // still catches a buffer being flushed at the end, which is what "first == total"
                // was on the short clip.
                assertTrue(
                    firstSegmentMs < totalMs * 0.9,
                    "first segment at ${firstSegmentMs.toInt()}ms of ${totalMs.toInt()}ms — that is " +
                        "a buffer being flushed, not a stream",
                )
            } finally {
                koi.unloadAll()
            }
        }
    }
}
