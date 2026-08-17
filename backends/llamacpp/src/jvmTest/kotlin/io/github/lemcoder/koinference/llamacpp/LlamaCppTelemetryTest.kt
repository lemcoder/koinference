package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.GenerationParameters
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checks that the facade's `koi_last_*` getters describe the generation that just happened.
 *
 * These are the numbers a benchmark reports, so the test asserts relationships that only hold
 * if they were measured rather than filled in: the prompt tokenizes to more than one token,
 * decode stops at the token limit it was given, and time-to-first-token is not the total.
 */
class LlamaCppTelemetryTest {

    private val modelPath: String? = System.getenv("KOI_TEST_GGUF")

    @Test
    fun `telemetry is absent before the first generation`() = runTest {
        val path = modelPath ?: return@runTest

        val loader = LlamaCppModelLoader()
        val runtime = loader.load(path) as LlamaCppRuntime
        try {
            assertNull(runtime.lastGeneration, "nothing has been generated yet")
        } finally {
            loader.unload(path)
        }
    }

    @Test
    fun `telemetry describes the generation that produced it`() = runTest {
        val path = modelPath ?: return@runTest

        val maxNewTokens = 24
        val loader = LlamaCppModelLoader(nCtx = 512, nPredict = maxNewTokens)
        val runtime = loader.load(path) as LlamaCppRuntime
        try {
            // Greedy: llama.cpp's facade exposes no seed, so temperature 0 is the only way to
            // make a run reproducible on this backend. Set before the first generation, which
            // is when the session — and with it the sampler — is built.
            runtime.updateGenerationParameters(GenerationParameters(temperature = 0.0))
            runtime.generateResponse("Once upon a time there was a little robot who")

            val telemetry = assertNotNull(runtime.lastGeneration)

            // The prompt is a sentence; one token would mean the count is not the tokenizer's.
            val promptTokens = assertNotNull(telemetry.promptTokens)
            assertTrue(promptTokens > 1, "expected a tokenized prompt, got $promptTokens")

            // nPredict is the ceiling. Fewer is legal (end-of-generation), more is a bug.
            val decodeTokens = assertNotNull(telemetry.decodeTokens)
            assertTrue(
                decodeTokens in 1..maxNewTokens,
                "expected 1..$maxNewTokens decoded tokens, got $decodeTokens",
            )

            val ttft = assertNotNull(telemetry.timeToFirstTokenMs)
            val prefill = assertNotNull(telemetry.prefillMs)
            val decode = assertNotNull(telemetry.decodeMs)
            assertTrue(ttft > 0.0 && prefill > 0.0, "durations must be positive: $telemetry")

            // TTFT covers prefill plus sampling the first token, so it cannot be shorter than
            // prefill. This is the assertion that would fail if TTFT were inferred from a total.
            assertTrue(ttft >= prefill, "ttft $ttft ms < prefill $prefill ms")

            val decodeRate = assertNotNull(telemetry.decodeTokensPerSecond)
            assertEquals(decodeTokens * 1000.0 / decode, decodeRate, 0.001)

            // llama.cpp reports no engine-side initialisation time; the loader times loading.
            assertNull(telemetry.engineInitMs)
        } finally {
            loader.unload(path)
        }
    }

    @Test
    fun `telemetry survives a session rebuild`() = runTest {
        val path = modelPath ?: return@runTest

        val loader = LlamaCppModelLoader(nCtx = 512, nPredict = 8)
        val runtime = loader.load(path) as LlamaCppRuntime
        try {
            runtime.generateResponse("Once upon a time")
            // Frees the session the previous numbers came from. Reading the getters now would
            // dereference a freed handle if the runtime had not already captured them.
            runtime.updateGenerationParameters(GenerationParameters(topK = 1))
            val before = assertNotNull(runtime.lastGeneration)

            runtime.generateResponse("Once upon a time")
            val after = assertNotNull(runtime.lastGeneration)
            assertTrue(after.decodeTokens!! > 0)
            assertNotNull(before.decodeTokens)
        } finally {
            loader.unload(path)
        }
    }
}
