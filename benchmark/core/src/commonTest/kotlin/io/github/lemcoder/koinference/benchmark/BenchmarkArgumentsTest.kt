package io.github.lemcoder.koinference.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules that decide what a run measures, tested off-device.
 *
 * These used to live inside the Android instrumentation, where a wrong default was only
 * observable by reading a results file after an emulator run.
 */
class BenchmarkArgumentsTest {

    private val corpusIds = listOf(
        "short_generation_v1", "long_generation_v1", "long_context_v1", "reasoning_v1",
    )

    private fun config(vararg pairs: Pair<String, String>) = BenchmarkArguments.toConfig(
        arguments = mapOf("model" to "/models/test.gguf", *pairs),
        corpusPromptIds = corpusIds,
        runIdFallback = "fallback-run",
    )

    @Test
    fun `a model path is required`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            BenchmarkArguments.toConfig(emptyMap(), corpusIds, "run")
        }
        assertTrue(failure.message!!.contains("-e model"))
    }

    @Test
    fun `defaults match what the harness documents`() {
        val config = config()

        assertEquals(BenchmarkArguments.DEFAULT_WARMUP, config.warmupIterations)
        assertEquals(BenchmarkArguments.DEFAULT_ITERATIONS, config.measurementIterations)
        assertEquals(BenchmarkArguments.DEFAULT_SEED, config.sampling.seed)
        // Greedy by default: llama.cpp's facade exposes no seed, so temperature 0 is the only
        // setting that makes both engines reproducible in the same way.
        assertEquals(0.0, config.sampling.temperature)
        assertEquals(0, config.sustainedDurationSeconds)
        assertEquals("fallback-run", config.benchmarkRunId)
    }

    @Test
    fun `an absent engine argument means every engine`() {
        assertEquals(listOf("all"), config().engineIds)
    }

    @Test
    fun `engine ids are split and trimmed`() {
        assertEquals(
            listOf("llama.cpp", "litert-lm"),
            config("engine" to " llama.cpp , litert-lm ").engineIds,
        )
    }

    @Test
    fun `the default prompt set is three workloads`() {
        assertEquals(
            listOf("short_generation_v1", "long_generation_v1", "long_context_v1"),
            config().workloads.map { it.promptId },
        )
    }

    @Test
    fun `the all prompt set is the whole corpus`() {
        assertEquals(corpusIds, config("promptSet" to "all").workloads.map { it.promptId })
    }

    @Test
    fun `an explicit prompt set is taken as a list of ids`() {
        assertEquals(
            listOf("reasoning_v1", "short_generation_v1"),
            config("promptSet" to "reasoning_v1, short_generation_v1").workloads.map { it.promptId },
        )
    }

    @Test
    fun `a long generation prompt gets a budget that fits it`() {
        // Capped at the default 128 it would measure something other than long generation.
        assertEquals(512, BenchmarkArguments.budgetFor("long_generation_v1", 128))
        assertEquals(384, BenchmarkArguments.budgetFor("reasoning_v1", 128))
        assertEquals(128, BenchmarkArguments.budgetFor("short_generation_v1", 128))
    }

    @Test
    fun `a larger requested budget is not reduced to the minimum`() {
        assertEquals(1024, BenchmarkArguments.budgetFor("long_generation_v1", 1024))
    }

    @Test
    fun `an unparseable number falls back rather than becoming zero`() {
        // A zero iteration count would produce an empty, successful-looking record.
        val config = config("iterations" to "many", "maxNewTokens" to "lots")

        assertEquals(BenchmarkArguments.DEFAULT_ITERATIONS, config.measurementIterations)
        assertEquals(
            BenchmarkArguments.DEFAULT_MAX_NEW_TOKENS,
            config.workloads.first { it.promptId == "short_generation_v1" }.maxNewTokens,
        )
    }

    @Test
    fun `gpu is opt-in and only for an exact boolean`() {
        assertEquals(false, config().model.useGpu)
        assertEquals(true, config("gpu" to "true").model.useGpu)
        assertEquals(false, config("gpu" to "yes").model.useGpu)
    }

    @Test
    fun `an explicit model identity always wins over the filename`() {
        val config = config("modelId" to "LFM2.5-1.2B", "quantization" to "int4")

        assertEquals("LFM2.5-1.2B", config.model.modelId)
        assertEquals("int4", config.model.quantization)
    }

    @Test
    fun `the sha is recorded only when given`() {
        assertNull(config().model.sha256)
        assertEquals("abc123", config("modelSha256" to "abc123").model.sha256)
    }
}
