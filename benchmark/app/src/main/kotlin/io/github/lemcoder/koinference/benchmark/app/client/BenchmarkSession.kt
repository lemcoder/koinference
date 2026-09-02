package io.github.lemcoder.koinference.benchmark.app.client

import android.content.Context
import io.github.lemcoder.koinference.benchmark.app.service.ModelFiles
import java.io.File

/**
 * Runs a matrix of engines, one after another, and collects their results files.
 *
 * Shared by the UI and by [io.github.lemcoder.koinference.benchmark.app.service.BenchmarkService]
 * so a scripted run and a tapped one measure the same thing. Everything about *what* a run means
 * lives here; the two callers only decide where the answer goes.
 */
class BenchmarkSession(
    private val context: Context,
    private val connections: Map<BackendProcess, BackendConnection>,
) {

    /** One engine's part of a run. */
    data class Outcome(
        val process: BackendProcess,
        val modelPath: String,
        val resultsJson: String?,
        val failure: String?,
    )

    /**
     * Runs [targets] sequentially, never in parallel: two engines decoding at once share an SoC and
     * a thermal budget, and the numbers would describe the contention.
     *
     * Each engine's process is stopped once its part finishes. Left alive it holds its weights, and
     * the next engine is measured under memory pressure it would not otherwise meet — an engine
     * that gives 12.2 tok/s alone measured 2.4 running third behind two resident models.
     */
    suspend fun run(
        targets: List<Pair<BackendProcess, String>>,
        options: Map<String, String>,
        onProgress: (String) -> Unit = {},
    ): List<Outcome> = targets.map { (process, modelPath) ->
        val connection = connections.getValue(process)
        val outcome = runCatching {
            connection.runBenchmark(modelPath, options, onProgress)
        }.fold(
            onSuccess = { Outcome(process, modelPath, it, null) },
            onFailure = { Outcome(process, modelPath, null, it.message ?: it::class.java.simpleName) },
        )
        connection.stopService()
        outcome
    }

    /**
     * The model each named engine should run, or a reason it cannot run.
     *
     * [modelPath] applies to every engine that can read that container — one file, two GGUF engines
     * — and an engine that cannot falls back to whatever it found on the device.
     */
    suspend fun resolveTargets(
        processes: List<BackendProcess>,
        modelPath: String?,
    ): Pair<List<Pair<BackendProcess, String>>, List<String>> {
        val targets = mutableListOf<Pair<BackendProcess, String>>()
        val skipped = mutableListOf<String>()

        for (process in processes) {
            val connection = connections.getValue(process)
            val refusal = runCatching { connection.unsupportedReason() }.getOrElse { it.message }
            if (refusal != null) {
                skipped += "${process.label}: $refusal"
                continue
            }

            // modelPaths() is already filtered by the backend's own handles(), so membership is
            // the test for "this engine can read that file" — no extension matching here.
            val readable = runCatching { connection.modelPaths() }.getOrDefault(emptyList())
            val chosen = when {
                modelPath == null -> readable.firstOrNull()
                modelPath in readable -> modelPath
                else -> null
            }

            when {
                chosen != null -> targets += process to chosen
                modelPath != null -> skipped +=
                    "${process.label}: cannot read ${File(modelPath).name}"
                else -> skipped += "${process.label}: no model it can read on this device"
            }
        }

        return targets to skipped
    }

    /** Models on the device, whichever engine reads them — for a run that named nothing. */
    fun discovered(): List<String> = ModelFiles.searchPaths(context)
        .flatMap { it.listFiles()?.toList().orEmpty() }
        .filter { it.isFile }
        .map { it.name }
        .distinct()
}
