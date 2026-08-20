package io.github.lemcoder.koinference.benchmark

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lemcoder.koinference.benchmark.config.BenchmarkArguments
import io.github.lemcoder.koinference.benchmark.platform.BenchmarkContext
import io.github.lemcoder.koinference.benchmark.prompts.PromptCorpus
import io.github.lemcoder.koinference.benchmark.result.BenchmarkFile
import io.github.lemcoder.koinference.benchmark.result.toJson
import io.github.lemcoder.koinference.benchmark.result.BenchmarkStatus
import io.github.lemcoder.koinference.benchmark.runner.BenchmarkRunner
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

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

            // Everything about turning arguments into a run lives in commonMain and is unit
            // tested there; this class supplies the two things only a device can — the Bundle and
            // a clock.
            val config = BenchmarkArguments.toConfig(
                arguments = buildMap {
                    arguments.keySet().forEach { key ->
                        arguments.getString(key)?.let { put(key, it) }
                    }
                    // The app's own cache, unless the caller named one. /data/local/tmp is
                    // shell's: a model can be read from there but a cache cannot be written
                    // beside it, and the delegate then rebuilds its prefill signatures per load.
                    putIfAbsent("cacheDir", context.cacheDir.absolutePath)
                },
                corpusPromptIds = corpus.prompts.map { it.id },
                runIdFallback = "local-${System.currentTimeMillis()}",
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

    private companion object {
        const val TAG = "koinference-benchmark"
    }
}
