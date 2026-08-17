package io.github.lemcoder.koinference.benchmark

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The entry point Firebase Test Lab runs.
 *
 * Everything is an instrumentation argument, so one engine, one workload or one iteration count
 * can be run on its own and a single failed matrix shard can be retried without re-running the
 * rest:
 *
 *     adb shell am instrument -w \
 *       -e engine llama.cpp \
 *       -e model /sdcard/Android/data/.../files/models/stories260K.gguf \
 *       -e iterations 5 \
 *       io.github.lemcoder.koinference.benchmark.test/androidx.test.runner.AndroidJUnitRunner
 *
 * `-e engine all` runs every engine in one process. That is offered for convenience and is
 * *not* how a comparison should be produced: the second engine starts on a heap the first one
 * grew, a page cache it warmed and an SoC it heated. The runner marks those records so the
 * contamination is visible in the results rather than only in a README. For a clean
 * comparison, invoke this test once per engine — which is what `run-ftl-benchmark.sh` does.
 *
 * The test itself never fails on a slow or bad measurement. It fails only when the harness
 * could not produce a result file at all: a red instrumentation run means "no data", and
 * anything about the numbers belongs in the JSON, where the analysis tool can see it.
 */
class BenchmarkInstrumentation {

    @Test
    fun runBenchmark() {
        val arguments = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        BenchmarkContext.applicationContext = context.applicationContext

        val log = StringBuilder()
        fun note(line: String) {
            log.append(line).append('\n')
            android.util.Log.i(TAG, line)
        }

        val outputDir = File(
            arguments.getString("outputDir")
                ?: context.getExternalFilesDir(null)?.absolutePath
                ?: context.filesDir.absolutePath,
        ).also { it.mkdirs() }

        val resultsFile = File(outputDir, "benchmark-results.json")
        val logFile = File(outputDir, "benchmark-log.txt")

        note("benchmark starting on ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        note("arguments: " + arguments.keySet().joinToString { "$it=${arguments.getString(it)}" })

        try {
            val modelPath = requireNotNull(arguments.getString("model")) {
                "-e model <path> is required: the harness never guesses where the weights are"
            }
            check(File(modelPath).isFile) {
                "Model not found at $modelPath. Push it to the device first; " +
                    "the APK does not carry weights."
            }

            val corpus = PromptCorpus.parse(loadCorpus(arguments.getString("promptFile")))
            val engines = (arguments.getString("engine") ?: "all")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val maxNewTokens = arguments.getString("maxNewTokens")?.toIntOrNull() ?: 128
            val workloads = (arguments.getString("promptSet") ?: "default")
                .let { set -> workloadsFor(set, corpus.prompts.map { it.id }, maxNewTokens) }

            val config = BenchmarkConfig(
                benchmarkRunId = arguments.getString("runId") ?: "local-${System.currentTimeMillis()}",
                engineIds = engines,
                model = BenchmarkModelConfig(
                    modelId = arguments.getString("modelId") ?: File(modelPath).nameWithoutExtension,
                    modelVersion = arguments.getString("modelVersion") ?: "unknown",
                    modelPath = modelPath,
                    // Recorded, never inferred: quantization cannot be read off a file size,
                    // and a wrong label here makes two incomparable runs look comparable.
                    quantization = arguments.getString("quantization") ?: "unknown",
                    sha256 = arguments.getString("modelSha256"),
                    maxContextTokens = arguments.getString("maxContextTokens")?.toIntOrNull() ?: 0,
                    threads = arguments.getString("threads")?.toIntOrNull() ?: 0,
                    useGpu = arguments.getString("gpu")?.toBooleanStrictOrNull() ?: false,
                ),
                workloads = workloads,
                sampling = SamplingConfig(
                    temperature = arguments.getString("temperature")?.toDoubleOrNull() ?: 0.0,
                    topK = arguments.getString("topK")?.toIntOrNull(),
                    topP = arguments.getString("topP")?.toDoubleOrNull(),
                    seed = arguments.getString("seed")?.toIntOrNull() ?: 42,
                ),
                warmupIterations = arguments.getString("warmup")?.toIntOrNull() ?: 1,
                measurementIterations = arguments.getString("iterations")?.toIntOrNull() ?: 5,
                sustainedDurationSeconds = arguments.getString("sustainedDurationSeconds")?.toIntOrNull() ?: 0,
                ftlModelId = arguments.getString("ftlModelId"),
                ftlVersion = arguments.getString("ftlVersion"),
            )

            val file = runBlocking {
                BenchmarkRunner(config, corpus, log = ::note).run()
            }

            resultsFile.writeText(file.toJson())
            note("wrote ${resultsFile.absolutePath}")
            summarize(file, ::note)

            // The run produced a file, which is all this test asserts. Records inside it may
            // have failed; that is data, not a test failure, and the analysis tool excludes
            // them from statistics.
            assertTrue("no records were produced", file.records.isNotEmpty())
        } catch (failure: Throwable) {
            note("benchmark aborted: ${failure::class.java.simpleName}: ${failure.message}")
            note(failure.stackTraceToString())
            // No results file is written on an abort. A partial file that looked like a
            // measurement would be worse than none, and the log says what happened.
            resultsFile.delete()
            throw failure
        } finally {
            logFile.writeText(log.toString())
        }
    }

    private fun summarize(file: BenchmarkFile, note: (String) -> Unit) {
        file.records.forEach { record ->
            when (record.status) {
                BenchmarkStatus.SUCCESS -> note(
                    "${record.engine.id}/${record.workload.promptId}: ${record.samples.size} samples, " +
                        "median wall clock ${record.samples.map { it.wallClockMs }.sorted()
                            .getOrNull(record.samples.size / 2)}ms",
                )

                else -> note(
                    "${record.engine.id}/${record.workload.promptId}: ${record.status} " +
                        "(${record.failureReason})",
                )
            }
        }
    }

    /**
     * Loads the prompt corpus from a file pushed to the device.
     *
     * Pushed rather than packaged into the APK: the device-test variant does not package
     * assets, and pushing it next to the model is one step either way. Required, not defaulted
     * — a run with no corpus is a configuration error, and inventing prompts here would make
     * two runs incomparable while both looked fine.
     */
    private fun loadCorpus(path: String?): String {
        val corpusPath = requireNotNull(path) {
            "-e promptFile <path> is required; push benchmark/fixtures/prompts.json to the device"
        }
        val file = File(corpusPath)
        check(file.isFile) { "No prompt corpus at $corpusPath" }
        return file.readText()
    }

    private fun workloadsFor(set: String, allIds: List<String>, maxNewTokens: Int): List<WorkloadConfig> =
        when (set) {
            // The full corpus, each with the token budget its prompt was written for: a
            // long-generation prompt capped at 128 tokens would measure something else.
            "all" -> allIds.map { WorkloadConfig(it, budgetFor(it, maxNewTokens)) }
            "default" -> listOf(
                WorkloadConfig("short_generation_v1", budgetFor("short_generation_v1", maxNewTokens)),
                WorkloadConfig("long_generation_v1", budgetFor("long_generation_v1", maxNewTokens)),
                WorkloadConfig("long_context_v1", budgetFor("long_context_v1", maxNewTokens)),
            )

            else -> set.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .map { WorkloadConfig(it, budgetFor(it, maxNewTokens)) }
        }

    private fun budgetFor(promptId: String, requested: Int): Int = when {
        promptId.startsWith("long_generation") -> maxOf(requested, 512)
        promptId.startsWith("reasoning") -> maxOf(requested, 384)
        else -> requested
    }

    private companion object {
        const val TAG = "koinference-benchmark"
    }
}
