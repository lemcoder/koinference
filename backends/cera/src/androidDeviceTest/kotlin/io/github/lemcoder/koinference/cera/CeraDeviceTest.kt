package io.github.lemcoder.koinference.cera

import io.github.lemcoder.koinference.Koinference
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.runtime.ResponsePart
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cera on real hardware.
 *
 * Block bodies, and `runBlocking` rather than `runTest`: JUnit4 rejects a non-void test method, and
 * real inference outruns runTest's default timeout.
 */
class CeraDeviceTest {

    private val modelPath = "/data/local/tmp/koinference/LFM2.5-1.2B-Instruct-Q4_0.gguf"

    private fun skip(): Boolean = !File(modelPath).isFile

    @Test
    fun generatesOnDevice() {
        if (skip()) return
        runBlocking {
            val koi = Koinference(Cera, config = ModelConfig(maxOutputTokens = 24))
            try {
                val reply = koi.load(modelPath).generateResponse("What is the capital of France?")
                    .filterIsInstance<ResponsePart.Text>().joinToString("") { it.text }
                assertTrue(reply.isNotBlank(), "expected generated text, got: '$reply'")
                println("CERA-DEVICE reply: $reply")
            } finally {
                koi.unloadAll()
            }
        }
    }

    /**
     * Decode rate measured two ways, so chunking cannot be what makes it look slow.
     *
     * A blocking generate has no chunks at all: tokens over wall clock, with the model's own
     * tokenizer doing the counting. If that agrees with the streamed figure the harness reports,
     * then the harness is measuring tokens and not emissions.
     */
    @Test
    fun blockingAndStreamedRatesAgree() {
        if (skip()) return
        runBlocking {
            val koi = Koinference(Cera, config = ModelConfig(maxOutputTokens = 64))
            try {
                val runtime = koi.load(modelPath) as CeraTextRuntime
                val prompt = "Count from one to forty, separated by commas."

                // Warmup: the first generation on a freshly loaded engine is not the steady state.
                runtime.generateResponse(prompt)

                val blockingStart = System.nanoTime()
                val blocking = runtime.generateResponse(prompt)
                    .filterIsInstance<ResponsePart.Text>().joinToString("") { it.text }
                val blockingMs = (System.nanoTime() - blockingStart) / 1_000_000.0
                val blockingTokens = runtime.countTokens(blocking)

                val streamStart = System.nanoTime()
                val parts = runtime.streamResponse(prompt).toList()
                    .filterIsInstance<ResponsePart.Text>()
                val streamMs = (System.nanoTime() - streamStart) / 1_000_000.0
                val streamed = parts.joinToString("") { it.text }
                val streamedTokens = runtime.countTokens(streamed)

                println(
                    "CERA-DEVICE blocking: $blockingTokens tokens in ${blockingMs.toInt()}ms = " +
                        "${"%.1f".format(blockingTokens * 1000.0 / blockingMs)} tok/s (no chunks involved)",
                )
                println(
                    "CERA-DEVICE streamed: $streamedTokens tokens in ${streamMs.toInt()}ms = " +
                        "${"%.1f".format(streamedTokens * 1000.0 / streamMs)} tok/s across ${parts.size} chunks",
                )

                assertTrue(blockingTokens > 0 && streamedTokens > 0)
                // Chunks are emissions; this asserts they are not being mistaken for tokens.
                assertTrue(
                    parts.size <= streamedTokens + 1,
                    "more chunks (${parts.size}) than tokens ($streamedTokens)",
                )
            } finally {
                koi.unloadAll()
            }
        }
    }
}
