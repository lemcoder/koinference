package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.ModelConfig
import io.github.lemcoder.koinference.ModelLoader
import io.github.lemcoder.koinference.litertlm.internal.EngineOptions
import io.github.lemcoder.koinference.litertlm.internal.LiteRtLmBridge
import io.github.lemcoder.koinference.litertlm.internal.platformBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads LiteRT-LM models.
 *
 * Give it a [ModelConfig.cacheDir] the process can write to before running anything large: without
 * one the runtime puts XNNPACK's weight cache beside the model, and when that is a directory the
 * app cannot write to, the delegate rebuilds every prefill signature on each load.
 */
class LiteRtLmModelLoader internal constructor(
    private val bridge: LiteRtLmBridge,
    private val config: ModelConfig,
) : ModelLoader {

    constructor(config: ModelConfig = ModelConfig()) : this(platformBridge(), config)

    private val runtimes = mutableMapOf<String, LiteRtLmRuntime>()

    // Held across the load itself, not only around the map. Two callers asking for the same
    // model would otherwise both miss the cache and both load the weights, and the one that
    // lost the race would be dropped from the map with no way left to free it. Loading a
    // second, different model has to wait — the alternative is a per-path lock, which is more
    // machinery than a loader that is normally used from one place needs.
    private val lock = Mutex()

    override suspend fun load(modelPath: String): LiteRtLmTextRuntime {
        // LiteRT-LM rejects a raw .tflite: weights have to be packaged in one of these two
        // containers, together with the tokenizer and metadata it needs.
        require(modelPath.endsWith(".litertlm") || modelPath.endsWith(".task")) {
            "LiteRT-LM expects a .litertlm or .task model path, got: $modelPath"
        }

        return lock.withLock {
            runtimes[modelPath] ?: newRuntime(modelPath).also { runtimes[modelPath] = it }
        }
    }

    override suspend fun unload(modelPath: String) {
        // Unlike the llama.cpp loader, dropping the reference is not enough: the engine is
        // native memory and would leak until the process exits.
        val runtime = lock.withLock { runtimes.remove(modelPath) }
        runtime?.close()
    }

    override suspend fun unloadAll() {
        val all = lock.withLock {
            runtimes.values.toList().also { runtimes.clear() }
        }
        all.forEach { it.close() }
    }

    private suspend fun newRuntime(modelPath: String): LiteRtLmRuntime {
        val options = EngineOptions(
            modelPath = modelPath,
            cacheDir = config.cacheDir,
            accelerator = config.settings.accelerator,
            nThreads = config.threads,
            maxTokens = config.contextTokens,
        )
        // Loading maps and prepares the weights, so it does not belong on the caller's
        // thread even though the handle it returns is just a pointer.
        val engine = withContext(Dispatchers.Default) { bridge.openEngine(options) }
        return LiteRtLmRuntime(
            bridge = bridge,
            engineOptions = options,
            systemPrompt = config.systemPrompt,
            engine = engine,
            parameters = config.parameters,
            maxOutputTokens = config.maxOutputTokens,
        )
    }
}
