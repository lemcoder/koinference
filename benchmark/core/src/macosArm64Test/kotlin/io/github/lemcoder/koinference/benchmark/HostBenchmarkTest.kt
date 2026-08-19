@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.benchmark

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv
import platform.posix.rewind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the whole protocol on the host, against real models, for whichever engines have one.
 *
 * This is where the harness is verified: macOS arm64 is the only target outside Android where
 * both engines really execute. What it cannot verify is the Android-only half — PSS, thermal
 * status, battery — and it asserts those are *absent* here rather than quietly zero, which is
 * the same contract the Android probe follows when a device cannot supply them.
 *
 *     KOI_TEST_GGUF=/path/stories260K.gguf \
 *     KOI_TEST_LITERTLM=/path/SmolLM2_135M_Instruct.litertlm \
 *     KOI_BENCH_OUT=/path/results \
 *         ./gradlew :benchmark:core:macosArm64Test
 */
class HostBenchmarkTest {

    private val ggufPath: String? = getenv("KOI_TEST_GGUF")?.toKString()
    private val liteRtLmPath: String? = getenv("KOI_TEST_LITERTLM")?.toKString()
    private val outputDir: String? = getenv("KOI_BENCH_OUT")?.toKString()

    private val corpus = PromptCorpus.parse(readFile(fixturePath()))

    @Test
    fun `the fixture corpus is loadable and every prompt has a checksum`() {
        assertTrue(corpus.prompts.isNotEmpty())
        corpus.prompts.forEach { prompt ->
            assertNotNull(prompt.sha256, "${prompt.id} has no checksum")
            assertTrue(prompt.text.isNotBlank(), "${prompt.id} is blank")
        }
        // The ids the workloads and the analysis tool refer to.
        listOf(
            "short_generation_v1", "long_generation_v1", "short_context_v1",
            "long_context_v1", "reasoning_v1", "summarization_v1",
        ).forEach { corpus.byId(it) }
    }

    @Test
    fun `a failing engine produces a FAILED record rather than a plausible one`() = runTest {
        // A path that cannot load: the run must complete and say so, because a benchmark that
        // dies on one combination loses the ones that would have worked.
        val file = BenchmarkRunner(
            config = config(engineIds = listOf("llama.cpp"), modelPath = "/nonexistent/model.gguf"),
            prompts = corpus,
        ).run()

        val record = file.records.single()
        assertEquals(BenchmarkStatus.FAILED, record.status)
        assertNotNull(record.failureReason)
        assertTrue(record.samples.isEmpty(), "a failed record must carry no measurements")
    }

    @Test
    fun `llama_cpp runs the protocol and the harness measures it`() = runTest {
        val path = ggufPath ?: return@runTest

        val file = BenchmarkRunner(
            config = config(engineIds = listOf("llama.cpp"), modelPath = path),
            prompts = corpus,
        ).run()

        val record = file.records.single()
        assertEquals(BenchmarkStatus.SUCCESS, record.status, record.failureReason ?: "")
        assertEquals(2, record.samples.size, "measurement iterations")
        assertEquals(1, record.warmupSamples.size, "warmup is kept apart from measurements")

        record.samples.forEach { sample ->
            val ttft = assertNotNull(sample.ttftMs, "time to first chunk")
            // llama.cpp emits one token per chunk, so the cap is observable from outside.
            assertTrue(sample.chunks in 1..MAX_NEW_TOKENS, "chunks ${sample.chunks}")
            // More than one, or the reply was buffered and ttft is really total latency.
            assertTrue(sample.chunks > 1, "expected a stream, got ${sample.chunks} chunk")
            assertTrue(ttft <= sample.wallClockMs, "ttft $ttft > wall clock ${sample.wallClockMs}")
            assertNotNull(sample.chunksPerSecond, "chunks/sec")
        }

        assertNotNull(record.initialization?.modelLoadMs)
        // Host, not a device: these have no honest value here and must not be invented.
        assertNull(record.memory, "no PSS off Android")
        assertNull(record.thermal, "no thermal readings off Android")
        assertNull(record.battery, "no battery readings off Android")
        assertNull(file.device.ftlModelId)

        writeIfRequested("host-llamacpp.json", file)
    }

    @Test
    fun `litert_lm is measured by exactly the same code`() = runTest {
        val path = liteRtLmPath ?: return@runTest

        val file = BenchmarkRunner(
            config = config(engineIds = listOf("litert-lm"), modelPath = path),
            prompts = corpus,
        ).run()

        val record = file.records.single()
        assertEquals(BenchmarkStatus.SUCCESS, record.status, record.failureReason ?: "")
        record.samples.forEach { sample ->
            assertTrue(sample.wallClockMs > 0.0)
            assertTrue(sample.outputChars > 0, "the model produced nothing")
            // The point of the rewrite: this leg reports no telemetry of its own, and time to
            // first token is available anyway, because the harness measures it.
            val ttft = assertNotNull(sample.ttftMs, "time to first chunk")
            assertTrue(ttft <= sample.wallClockMs, "ttft $ttft > wall clock ${sample.wallClockMs}")
            assertTrue(sample.chunks > 1, "expected a stream, got ${sample.chunks} chunk")
        }
        assertTrue(
            record.notes.any { it.contains("Chunks are emissions") },
            "a record must say what a chunk is: ${record.notes}",
        )

        writeIfRequested("host-litertlm.json", file)
    }

    @Test
    fun `both engines in one run are marked as sharing a process`() = runTest {
        val gguf = ggufPath ?: return@runTest
        val litertlm = liteRtLmPath ?: return@runTest

        // Both engines cannot share a model path, so this runs them as two configs and merges
        // the records — which is exactly what the Android runner does across two invocations,
        // minus the fresh process it gets and this does not.
        val first = BenchmarkRunner(config(listOf("llama.cpp"), gguf), corpus).run()
        val second = BenchmarkRunner(config(listOf("litert-lm"), litertlm), corpus).run()
        val merged = first.copy(records = first.records + second.records)

        assertEquals(2, merged.records.size)
        assertTrue(merged.records.all { it.status == BenchmarkStatus.SUCCESS })
        // Engine ids differ, so the analysis tool can group them; quantization differs too,
        // which is why it is recorded per record rather than per run.
        assertEquals(setOf("llama.cpp", "litert-lm"), merged.records.map { it.engine.id }.toSet())

        writeIfRequested("host-both.json", merged)
    }

    private fun config(engineIds: List<String>, modelPath: String) = BenchmarkConfig(
        benchmarkRunId = "host-verification",
        engineIds = engineIds,
        model = BenchmarkModelConfig(
            // Read off the file name so that two exports of the same weights report the same
            // modelId and differ only in quantization — which is what makes the comparability
            // section of the report meaningful rather than decorative.
            modelId = modelIdOf(modelPath),
            modelVersion = "test",
            modelPath = modelPath,
            quantization = quantizationOf(modelPath),
            maxContextTokens = 512,
        ),
        workloads = listOf(WorkloadConfig("short_generation_v1", MAX_NEW_TOKENS)),
        warmupIterations = 1,
        measurementIterations = 2,
    )

    private fun writeIfRequested(name: String, file: BenchmarkFile) {
        val dir = outputDir ?: return
        val json = file.toJson()
        // Round-trips before it is written: a file the analysis tool cannot parse is not a
        // result, and finding that out in Python later loses the run.
        assertEquals(file.records.size, parseBenchmarkFile(json).records.size)
        writeFile("$dir/$name", json)
    }

    private companion object {
        const val MAX_NEW_TOKENS = 32
    }
}

// modelIdOf and quantizationOf moved to commonMain (ModelNaming.kt), where they are unit tested
// rather than only exercised by whichever model a developer happened to point this at.

private fun fixturePath(): String {
    // The test runs from the module directory; the fixtures live beside the modules.
    val fromEnv = getenv("KOI_BENCH_FIXTURES")?.toKString()
    return fromEnv ?: "../fixtures/prompts.json"
}

private fun readFile(path: String): String = memScoped {
    val handle = fopen(path, "rb") ?: error("Cannot open $path")
    try {
        fseek(handle, 0, SEEK_END)
        val size = ftell(handle).toInt()
        rewind(handle)
        val buffer = allocArray<ByteVar>(size + 1)
        fread(buffer, 1.convert(), size.convert(), handle)
        buffer[size] = 0
        buffer.toKString()
    } finally {
        fclose(handle)
    }
}

private fun writeFile(path: String, text: String) {
    val handle = fopen(path, "w") ?: error("Cannot write $path")
    try {
        fputs(text, handle)
    } finally {
        fclose(handle)
    }
}
