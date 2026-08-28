package io.github.lemcoder.koinference.benchmark.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lemcoder.koinference.benchmark.app.client.BackendConnection
import io.github.lemcoder.koinference.benchmark.app.client.BackendProcess
import io.github.lemcoder.koinference.benchmark.app.net.WebServerController
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the two screens share: which engines exist, which are runnable here, and what the last run
 * produced.
 *
 * Every engine call goes through a [BackendConnection], so nothing in this class touches a model.
 * That is the point of the process split — the UI process stays a UI process, and a results file's
 * memory numbers describe the engine rather than Compose.
 */
class BenchmarkViewModel(private val context: Context) : ViewModel() {

    private val connections = BackendProcess.entries.associateWith {
        BackendConnection(context, it.serviceClass)
    }

    private val _backends = MutableStateFlow(BackendProcess.entries.map { BackendState(it) })
    val backends: StateFlow<List<BackendState>> = _backends.asStateFlow()

    private val _selected = MutableStateFlow<Set<BackendProcess>>(emptySet())
    val selected: StateFlow<Set<BackendProcess>> = _selected.asStateFlow()

    private val _run = MutableStateFlow<RunState>(RunState.Idle)
    val run: StateFlow<RunState> = _run.asStateFlow()

    private val _serving = MutableStateFlow<ServingState>(ServingState.Stopped)
    val serving: StateFlow<ServingState> = _serving.asStateFlow()

    private val server = WebServerController(context) { process -> connections.getValue(process) }

    init {
        probeBackends()
    }

    /**
     * Binds every engine to ask two questions: can this device run it, and is there a model for it.
     *
     * Done up front, because both answers change what the list can offer and neither is knowable
     * without the engine's own process.
     */
    private fun probeBackends() {
        BackendProcess.entries.forEach { process ->
            viewModelScope.launch {
                val connection = connections.getValue(process)
                val state = runCatching {
                    val reason = connection.unsupportedReason()
                    val models = if (reason == null) connection.modelPaths() else emptyList()
                    BackendState(
                        process = process,
                        engineId = connection.backendId(),
                        models = models,
                        selectedModel = models.firstOrNull(),
                        unsupportedReason = reason,
                    )
                }.getOrElse { failure ->
                    BackendState(process = process, probeFailure = failure.message ?: "cannot reach the service")
                }
                _backends.update { list -> list.map { if (it.process == process) state else it } }
            }
        }
    }

    fun toggle(process: BackendProcess) {
        _selected.update { current ->
            if (process in current) current - process else current + process
        }
    }

    fun selectModel(process: BackendProcess, modelPath: String) {
        _backends.update { list ->
            list.map { if (it.process == process) it.copy(selectedModel = modelPath) else it }
        }
    }

    /**
     * Runs the suite on every selected engine, one after another.
     *
     * Sequentially and never in parallel: two engines decoding at once share an SoC and a thermal
     * budget, and the numbers would describe the contention rather than the engines.
     */
    fun runBenchmark() {
        if (_run.value is RunState.Running) return
        val chosen = _backends.value.filter { it.process in _selected.value && it.runnable }
        if (chosen.isEmpty()) return

        _run.value = RunState.Running("starting")
        viewModelScope.launch {
            val results = mutableListOf<String>()
            val failures = mutableListOf<String>()

            for (state in chosen) {
                val model = state.selectedModel ?: continue
                _run.value = RunState.Running("${state.process.label}: loading ${File(model).name}")
                runCatching {
                    connections.getValue(state.process).runBenchmark(
                        modelPath = model,
                        options = SUITE,
                        onProgress = { line -> _run.value = RunState.Running(line) },
                    )
                }.onSuccess { results += it }
                    .onFailure { failures += "${state.process.label}: ${it.message}" }

                // The finished engine's process goes away before the next one starts. Left alive it
                // holds its weights, and the engine measured after it runs against that pressure:
                // measured 2.4 tok/s for an engine that gives 12.2 alone on the same device.
                connections.getValue(state.process).stopService()
            }

            _run.value = if (results.isEmpty() && failures.isNotEmpty()) {
                RunState.Failed(failures.joinToString("\n"))
            } else {
                RunState.Finished(
                    rows = withContext(Dispatchers.Default) { ResultsTable.rows(results) },
                    resultsJson = results,
                    failures = failures,
                )
            }
        }
    }

    fun dismissResults() {
        _run.value = RunState.Idle
    }

    /** Starts the HTTP server in this process, serving whichever engine was chosen. */
    fun startServing(process: BackendProcess) {
        val state = _backends.value.first { it.process == process }
        val model = state.selectedModel ?: return
        _serving.value = ServingState.Starting(process)
        viewModelScope.launch {
            _serving.value = runCatching { server.start(process, model) }
                .fold(
                    onSuccess = { ServingState.Serving(process, File(model).name, it) },
                    onFailure = { ServingState.Failed(it.message ?: "could not start") },
                )
        }
    }

    fun stopServing() {
        viewModelScope.launch {
            server.stop()
            _serving.value = ServingState.Stopped
        }
    }

    override fun onCleared() {
        connections.values.forEach { it.disconnect() }
        super.onCleared()
    }

    private companion object {
        /**
         * The on-device suite: short enough to sit through with a phone in your hand.
         *
         * One warmup discarded and three measured, because LiteRT-LM's first generation on a fresh
         * engine differs from every later one.
         *
         * **One workload, named explicitly.** The harness's default set includes
         * `long_generation_v1`, whose budget floors at 512 tokens however small a `maxNewTokens`
         * is asked for — correctly, since a long-generation workload capped at 32 measures
         * something else. Three engines through that is over twenty minutes of decoding, which is
         * a shell job rather than something to hold a phone through; `WebServerService` and the
         * instrumentation runner are how a full sweep is driven.
         */
        val SUITE = mapOf(
            "promptSet" to "short_generation_v1",
            "iterations" to "3",
            "warmup" to "1",
            "maxNewTokens" to "32",
        )
    }
}
