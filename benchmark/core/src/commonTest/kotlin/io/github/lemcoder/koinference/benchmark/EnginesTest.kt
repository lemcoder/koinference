package io.github.lemcoder.koinference.benchmark

import io.github.lemcoder.koinference.benchmark.config.BenchmarkModelConfig
import io.github.lemcoder.koinference.benchmark.config.SamplingConfig
import io.github.lemcoder.koinference.benchmark.config.WorkloadConfig
import io.github.lemcoder.koinference.benchmark.engine.availableEngines
import io.github.lemcoder.koinference.benchmark.engine.benchmarkBackends
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The harness has one adapter for every backend, so what is worth asserting is that the registry
 * it exposes lines up with the ids configs and results use.
 */
class EnginesTest {

    @Test
    fun everyRegisteredBackendIsBenchmarkable() {
        assertEquals(benchmarkBackends().map { it.id }, availableEngines().map { it.id })
    }

    @Test
    fun theOrderIsTheOneEngineAllRunsIn() {
        // The first engine in a process is the only one that sees an untouched heap and a cold
        // SoC, so this order is part of what a run means.
        //
        // Only the first two are asserted: the list is per platform now, because Cera has no
        // Kotlin/Native leg to be present on. What every platform must agree on is that llama.cpp
        // goes first — it is also the engine that gets a `.gguf` asked for by path.
        assertEquals(listOf("llama.cpp", "litert-lm"), benchmarkBackends().take(2).map { it.id })
    }

    @Test
    fun metadataReportsWhichKnobsTheEngineActuallyApplied() {
        val engine = availableEngines().first { it.id == "llama.cpp" }
        engine.applyWorkload(WorkloadConfig("p", 32), SamplingConfig(seed = 7, topP = 0.9))

        val metadata = engine.metadata(
            BenchmarkModelConfig(
                modelId = "m", modelVersion = "1", modelPath = "/m/x.gguf", quantization = "q8_0",
            ),
        )

        // Requested, but this engine has no seed and no top-p; the record says so rather than
        // implying the run was seeded.
        assertEquals("false", metadata["seedApplied"])
        assertEquals("false", metadata["top_pApplied"])
        assertEquals("true", metadata["top_kApplied"])
        assertTrue(metadata.containsKey("maxNewTokens"))
    }
}
