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

class ModelNamingTest {

    @Test
    fun `the same weights give the same id across containers`() {
        assertEquals("LFM2.5-1.2B-Instruct", modelIdOf("/m/LFM2.5-1.2B-Instruct-Q4_0.gguf"))
        assertEquals("LFM2.5-1.2B-Instruct", modelIdOf("/m/LFM2.5-1.2B-Instruct_int4.litertlm"))
    }

    @Test
    fun `quantization comes from the name and is lowercased`() {
        assertEquals("q4_0", quantizationOf("/m/LFM2.5-1.2B-Instruct-Q4_0.gguf"))
        assertEquals("int4", quantizationOf("/m/LFM2.5-1.2B-Instruct_int4.litertlm"))
    }

    @Test
    fun `a name with no quantization label says so rather than guessing`() {
        assertEquals("unknown", quantizationOf("/m/stories260K.gguf"))
        assertEquals("stories260K", modelIdOf("/m/stories260K.gguf"))
    }

    @Test
    fun `a quantization-looking word that is not a suffix is left alone`() {
        assertEquals("int4-tuned", modelIdOf("/m/int4-tuned.gguf"))
        assertEquals("unknown", quantizationOf("/m/int4-tuned.gguf"))
    }

    @Test
    fun `every label the corpus of names uses round-trips`() {
        listOf("q4_0", "q4_k_m", "q5_k_m", "q6_k", "q8_0", "int4", "int8", "f16", "bf16", "f32")
            .forEach { label ->
                val path = "/m/Model-$label.gguf"
                assertEquals("Model", modelIdOf(path), "modelId for $label")
                assertEquals(label, quantizationOf(path), "quantization for $label")
            }
    }
}
