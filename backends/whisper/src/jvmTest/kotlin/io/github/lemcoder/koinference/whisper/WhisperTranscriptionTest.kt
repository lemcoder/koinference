package io.github.lemcoder.koinference.whisper

import io.github.lemcoder.koinference.Koinference
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.ResponsePart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real transcription through the JNI leg.
 *
 * Env-gated per backend, like the others: `KOI_TEST_WHISPER` is the model and `KOI_TEST_WAV` the
 * recording. `runBlocking`, not `runTest` — real inference outruns runTest's default timeout.
 */
class WhisperTranscriptionTest {

    private val model: String? = System.getenv("KOI_TEST_WHISPER")
    private val wav: String? = System.getenv("KOI_TEST_WAV")

    @Test
    fun `transcribes a recording`() {
        val modelPath = model ?: return
        val wavPath = wav ?: return

        runBlocking {
            val koi = Koinference(Whisper)
            try {
                val text = koi.load(modelPath)
                    .generateResponse(listOf(PromptPart.AudioFile(wavPath)))
                    .filterIsInstance<ResponsePart.Text>()
                    .joinToString("") { it.text }

                assertTrue(text.isNotBlank(), "expected a transcript, got: '$text'")
                println("whisper transcript: ${text.trim()}")
            } finally {
                koi.unloadAll()
            }
        }
    }

    @Test
    fun `streams segments as whisper produces them`() {
        val modelPath = model ?: return
        val wavPath = wav ?: return

        runBlocking {
            val koi = Koinference(Whisper)
            try {
                val started = System.nanoTime()
                val arrivals = mutableListOf<Double>()
                val segments = mutableListOf<String>()

                koi.load(modelPath).streamResponse(listOf(PromptPart.AudioFile(wavPath)))
                    .collect { part ->
                        if (part is ResponsePart.Text) {
                            arrivals += (System.nanoTime() - started) / 1e6
                            segments += part.text
                        }
                    }

                val total = (System.nanoTime() - started) / 1e6
                println(
                    "whisper streamed ${segments.size} segments over ${total.toInt()}ms; " +
                        "arrivals ${arrivals.map { it.toInt() }}",
                )

                assertTrue(segments.isNotEmpty(), "expected at least one segment")
                // The point of the queue in the facade: a segment reaches the caller while whisper
                // is still working on the rest. Arriving together would satisfy "more than one
                // chunk" while making time to first segment equal total latency.
                if (segments.size > 1) {
                    val first = arrivals.first()
                    assertTrue(
                        first < total * 0.9,
                        "first segment at ${first.toInt()}ms of ${total.toInt()}ms — that is a " +
                            "buffer being flushed, not a stream",
                    )
                }
            } finally {
                koi.unloadAll()
            }
        }
    }
}
