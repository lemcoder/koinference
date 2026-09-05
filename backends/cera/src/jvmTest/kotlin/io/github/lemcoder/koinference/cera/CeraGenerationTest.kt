package io.github.lemcoder.koinference.cera

import io.github.lemcoder.koinference.Koinference
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.runtime.ResponsePart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real generation through the published UniFFI bindings.
 *
 * Env-gated on `KOI_TEST_GGUF` — per backend, like the others, so that running both GGUF engines'
 * tests in one invocation does not hand either the other's container.
 *
 * `runBlocking`, not `runTest`: real inference outruns runTest's 60s default.
 */
class CeraGenerationTest {

    private val model: String? = System.getenv("KOI_TEST_GGUF")

    @Test
    fun `generates a reply`() {
        val path = model ?: return

        runBlocking {
            val koi = Koinference(Cera, config = ModelConfig(maxOutputTokens = 24))
            try {
                val reply = koi.load(path).generateResponse("What is the capital of France?").text()
                assertTrue(reply.isNotBlank(), "expected generated text, got: '$reply'")
                println("cera blocking reply: $reply")
            } finally {
                koi.unloadAll()
            }
        }
    }

    @Test
    fun `streams the reply in pieces`() {
        val path = model ?: return

        runBlocking {
            val koi = Koinference(Cera, config = ModelConfig(maxOutputTokens = 24))
            try {
                val parts = koi.load(path).streamResponse("Tell me a short story.")
                    .toList()
                    .filterIsInstance<ResponsePart.Text>()
                    .map { it.text }

                assertTrue(parts.size > 1, "expected a stream, got ${parts.size} part")
                println("cera streamed ${parts.size} parts: ${parts.joinToString("")}")
            } finally {
                koi.unloadAll()
            }
        }
    }

    /**
     * One chunk per token, so "chunks" and "tokens" cannot drift apart in a results file.
     *
     * Cera's default batches sixteen tokens per emission, which would make time to first chunk mean
     * time to the sixteenth token on hardware fast enough for the flush timer not to fire first.
     */
    @Test
    fun `streams one token per chunk`() {
        val path = model ?: return

        runBlocking {
            val koi = Koinference(Cera, config = ModelConfig(maxOutputTokens = 32))
            try {
                val runtime = koi.load(path) as CeraTextRuntime
                val parts = runtime.streamResponse("Count from one to twenty.").toList()
                    .filterIsInstance<ResponsePart.Text>()
                val tokens = runtime.countTokens(parts.joinToString("") { it.text })

                // Not equality: a chunk carries whole tokens, and the tokenizer may merge a pair
                // of them when it re-reads the finished text.
                assertTrue(
                    parts.size >= tokens - 2,
                    "expected roughly one chunk per token, got ${parts.size} chunks for $tokens tokens",
                )
            } finally {
                koi.unloadAll()
            }
        }
    }

    @Test
    fun `counts tokens with the model's own tokenizer`() {
        val path = model ?: return

        runBlocking {
            val koi = Koinference(Cera, config = ModelConfig(maxOutputTokens = 8))
            try {
                val runtime = koi.load(path) as CeraTextRuntime
                val count = runtime.countTokens("The capital of France is Paris.")

                // Content only: no BOS and no chat template, the same rule the other backends
                // follow, so a token means the same thing in every row of a results file.
                assertTrue(count in 1..16, "implausible token count: $count")
            } finally {
                koi.unloadAll()
            }
        }
    }
}
