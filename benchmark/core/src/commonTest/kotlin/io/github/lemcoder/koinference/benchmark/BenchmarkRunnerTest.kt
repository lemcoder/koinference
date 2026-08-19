package io.github.lemcoder.koinference.benchmark

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The protocol, without a model.
 *
 * All of this used to require macOS arm64 and two real runtimes, so a change to the bookkeeping
 * — which iterations count, what a failed record carries, which notes are attached — was only
 * checked when someone happened to have both model files on hand.
 */
class BenchmarkRunnerTest {

    private val corpus = PromptCorpus(
        corpusVersion = "test-v1",
        prompts = listOf(
            BenchmarkPrompt(id = "short_generation_v1", category = "generation", text = "Say hi."),
            BenchmarkPrompt(id = "empty_v1", category = "generation", text = ""),
        ),
    )

    private fun config(
        engineIds: List<String> = listOf("fake"),
        promptId: String = "short_generation_v1",
        warmup: Int = 1,
        iterations: Int = 3,
        sustainedSeconds: Int = 0,
    ) = BenchmarkConfig(
        benchmarkRunId = "test-run",
        engineIds = engineIds,
        model = BenchmarkModelConfig(
            modelId = "test-model",
            modelVersion = "1",
            modelPath = "/models/test.gguf",
            quantization = "q8_0",
        ),
        workloads = listOf(WorkloadConfig(promptId, maxNewTokens = 32)),
        warmupIterations = warmup,
        measurementIterations = iterations,
        sustainedDurationSeconds = sustainedSeconds,
    )

    @Test
    fun `warmup iterations are kept out of the measurements`() = runTest {
        val probe = FakePlatformProbe()
        val engine = FakeBenchmarkEngine(probe = probe)

        val record = BenchmarkRunner(
            config = config(warmup = 2, iterations = 3),
            prompts = corpus,
            probe = probe,
            engines = listOf(engine),
        ).run().records.single()

        assertEquals(BenchmarkStatus.SUCCESS, record.status)
        assertEquals(3, record.samples.size)
        assertEquals(2, record.warmupSamples.size)
        assertEquals(5, engine.generations, "warmup must actually run, not just be recorded")
        // Iterations are numbered from zero within their own phase.
        assertEquals(listOf(0, 1, 2), record.samples.map { it.iteration })
    }

    @Test
    fun `every sample carries what the harness measured`() = runTest {
        val probe = FakePlatformProbe()

        val record = BenchmarkRunner(
            config = config(warmup = 0, iterations = 1),
            prompts = corpus,
            probe = probe,
            engines = listOf(FakeBenchmarkEngine(probe = probe)),
        ).run().records.single()

        val sample = record.samples.single()
        assertEquals(3, sample.chunks)
        assertEquals(30.0, sample.wallClockMs)
        assertEquals(10.0, sample.ttftMs)
        // "Hello there": three chunks, eleven characters, two words for the fake tokenizer.
        assertEquals(11, sample.outputChars)
        assertEquals(2, sample.generatedTokens, "counted by the harness with the engine's tokenizer")
        assertNotNull(record.initialization?.modelLoadMs)
    }

    @Test
    fun `a failing engine produces a FAILED record rather than a plausible one`() = runTest {
        val probe = FakePlatformProbe()

        val record = BenchmarkRunner(
            config = config(),
            prompts = corpus,
            probe = probe,
            engines = listOf(
                FakeBenchmarkEngine(probe = probe, failOnInitialize = IllegalStateException("no weights")),
            ),
        ).run().records.single()

        assertEquals(BenchmarkStatus.FAILED, record.status)
        // Type and message: "failed" alone cannot be triaged from an artifact.
        assertEquals("IllegalStateException: no weights", record.failureReason)
        assertTrue(record.samples.isEmpty(), "a failed record must carry no measurements")
    }

    @Test
    fun `a failure part way through keeps what it collected under a FAILED status`() = runTest {
        val probe = FakePlatformProbe()

        val record = BenchmarkRunner(
            config = config(),
            prompts = corpus,
            probe = probe,
            engines = listOf(
                FakeBenchmarkEngine(probe = probe, failOnGenerate = IllegalStateException("decode died")),
            ),
        ).run().records.single()

        assertEquals(BenchmarkStatus.FAILED, record.status)
        assertTrue(record.failureReason!!.contains("decode died"))
    }

    @Test
    fun `one engine failing does not lose the others`() = runTest {
        val probe = FakePlatformProbe()

        val file = BenchmarkRunner(
            config = config(engineIds = listOf("broken", "fake")),
            prompts = corpus,
            probe = probe,
            engines = listOf(
                FakeBenchmarkEngine(id = "broken", probe = probe, failOnInitialize = RuntimeException("x")),
                FakeBenchmarkEngine(id = "fake", probe = probe),
            ),
        ).run()

        assertEquals(
            listOf(BenchmarkStatus.FAILED, BenchmarkStatus.SUCCESS),
            file.records.map { it.status },
        )
    }

    @Test
    fun `an empty prompt is skipped rather than measured`() = runTest {
        val probe = FakePlatformProbe()
        val engine = FakeBenchmarkEngine(probe = probe)

        val record = BenchmarkRunner(
            config = config(promptId = "empty_v1"),
            prompts = corpus,
            probe = probe,
            engines = listOf(engine),
        ).run().records.single()

        assertEquals(BenchmarkStatus.SKIPPED, record.status)
        assertEquals(0, engine.generations, "a skipped workload must not load or generate")
    }

    @Test
    fun `an unknown engine id fails loudly`() = runTest {
        val probe = FakePlatformProbe()

        val failure = runCatching {
            BenchmarkRunner(
                config = config(engineIds = listOf("nope")),
                prompts = corpus,
                probe = probe,
                engines = listOf(FakeBenchmarkEngine(probe = probe)),
            ).run()
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure.message!!.contains("nope"), failure.message!!)
    }

    @Test
    fun `the all keyword runs the registry it was given in order`() = runTest {
        val probe = FakePlatformProbe()

        val file = BenchmarkRunner(
            config = config(engineIds = listOf("all")),
            prompts = corpus,
            probe = probe,
            engines = listOf(
                FakeBenchmarkEngine(id = "first", probe = probe),
                FakeBenchmarkEngine(id = "second", probe = probe),
            ),
        ).run()

        assertEquals(listOf("first", "second"), file.records.map { it.engine.id })
    }

    @Test
    fun `only the first engine in a process is treated as uncontaminated`() = runTest {
        val probe = FakePlatformProbe()

        val file = BenchmarkRunner(
            config = config(engineIds = listOf("all")),
            prompts = corpus,
            probe = probe,
            engines = listOf(
                FakeBenchmarkEngine(id = "first", probe = probe),
                FakeBenchmarkEngine(id = "second", probe = probe),
            ),
        ).run()

        assertFalse(file.records[0].notes.any { it.contains("same process") })
        assertTrue(file.records[1].notes.any { it.contains("same process") })
    }

    @Test
    fun `the workload is applied to the engine before it loads`() = runTest {
        val probe = FakePlatformProbe()
        val engine = FakeBenchmarkEngine(probe = probe)

        BenchmarkRunner(
            config = config().copy(sampling = SamplingConfig(temperature = 0.7, topK = 5)),
            prompts = corpus,
            probe = probe,
            engines = listOf(engine),
        ).run()

        assertEquals(WorkloadConfig("short_generation_v1", 32), engine.appliedWorkload)
        assertEquals(SamplingConfig(temperature = 0.7, topK = 5), engine.appliedSampling)
    }

    @Test
    fun `a reply that arrives in one chunk is called out rather than timed as a first token`() =
        runTest {
            val probe = FakePlatformProbe()

            val record = BenchmarkRunner(
                config = config(warmup = 0, iterations = 2),
                prompts = corpus,
                probe = probe,
                engines = listOf(BufferingEngine(id = "fake", probe = probe)),
            ).run().records.single()

            val note = record.notes.firstOrNull { it.contains("single chunk") }
            assertNotNull(note, "a buffered reply must be marked: ${record.notes}")
            assertTrue(note.contains("2 of 2"))
            // The number is still recorded — it is just not presented as a first-token time.
            assertEquals(record.samples.first().wallClockMs, record.samples.first().ttftMs)
        }

    @Test
    fun `every record says what a chunk is`() = runTest {
        val probe = FakePlatformProbe()

        val record = BenchmarkRunner(
            config = config(),
            prompts = corpus,
            probe = probe,
            engines = listOf(FakeBenchmarkEngine(probe = probe)),
        ).run().records.single()

        assertTrue(record.notes.any { it.contains("Chunks are emissions") }, "${record.notes}")
    }

    @Test
    fun `an engine without a tokenizer reports no tokens rather than zero`() = runTest {
        val probe = FakePlatformProbe()

        val record = BenchmarkRunner(
            config = config(engineIds = listOf("no-tokenizer"), warmup = 0, iterations = 1),
            prompts = corpus,
            probe = probe,
            engines = listOf(TokenizerlessEngine(probe = probe)),
        ).run().records.single()

        assertNull(record.samples.single().generatedTokens)
        assertNull(record.samples.single().tokensPerSecond)
    }

    @Test
    fun `metrics the platform cannot supply stay absent`() = runTest {
        val probe = FakePlatformProbe()

        val record = BenchmarkRunner(
            config = config(),
            prompts = corpus,
            probe = probe,
            engines = listOf(FakeBenchmarkEngine(probe = probe)),
        ).run().records.single()

        // All-null in, null out: an object full of nulls suggests a failed reading rather than a
        // platform that has none of this.
        assertNull(record.memory)
        assertNull(record.thermal)
        assertNull(record.battery)
    }

    @Test
    fun `the session is closed even when the run succeeds`() = runTest {
        val probe = FakePlatformProbe()
        val engine = FakeBenchmarkEngine(probe = probe)

        BenchmarkRunner(
            config = config(),
            prompts = corpus,
            probe = probe,
            engines = listOf(engine),
        ).run()

        assertTrue(engine.closed, "the model was left loaded")
    }

    @Test
    fun `the FTL identity comes from the config rather than from the device`() = runTest {
        val probe = FakePlatformProbe()

        val file = BenchmarkRunner(
            config = config().copy(ftlModelId = "redfin", ftlVersion = "30"),
            prompts = corpus,
            probe = probe,
            engines = listOf(FakeBenchmarkEngine(probe = probe)),
        ).run()

        assertEquals("redfin", file.device.ftlModelId)
        assertEquals("30", file.device.ftlVersion)
    }

    @Test
    fun `results round-trip through the result schema`() = runTest {
        val probe = FakePlatformProbe()

        val file = BenchmarkRunner(
            config = config(),
            prompts = corpus,
            probe = probe,
            engines = listOf(FakeBenchmarkEngine(probe = probe)),
        ).run()

        val reparsed = parseBenchmarkFile(file.toJson())

        assertEquals(file.records.size, reparsed.records.size)
        assertEquals(file.records.single().samples, reparsed.records.single().samples)
    }
}
