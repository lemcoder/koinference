package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.ModelConfig
import io.github.lemcoder.koinference.ModelLoader
import io.github.lemcoder.koinference.llamacpp.internal.CpuPlacementPolicy
import io.github.lemcoder.koinference.llamacpp.internal.LlamaCppBridge
import io.github.lemcoder.koinference.llamacpp.internal.ModelOptions
import io.github.lemcoder.koinference.llamacpp.internal.platformBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads GGUF models through llama.cpp.
 *
 * [ModelConfig.cacheDir] is ignored: llama.cpp memory-maps the weights and keeps no prepared copy
 * beside them.
 */
class LlamaCppModelLoader internal constructor(
    private val bridge: LlamaCppBridge,
    private val config: ModelConfig,
    private val placementPolicy: CpuPlacementPolicy = CpuPlacementPolicy(),
) : ModelLoader {

    constructor(config: ModelConfig = ModelConfig()) : this(platformBridge(), config)

    private val runtimes = mutableMapOf<String, LlamaCppRuntime>()

    // Held across the load, not only around the map: two callers asking for the same model would
    // otherwise both load the weights, and the one that lost the race would be dropped from the
    // map with no way left to free it.
    private val lock = Mutex()

    override suspend fun load(modelPath: String): LlamaCppTextRuntime {
        require(modelPath.endsWith(".gguf")) {
            "llama.cpp loader expects a .gguf model path, got: $modelPath"
        }

        return lock.withLock {
            runtimes[modelPath] ?: newRuntime(modelPath).also { runtimes[modelPath] = it }
        }
    }

    override suspend fun unload(modelPath: String) {
        // Dropping the reference is not enough: the model and its session are native memory and
        // would live until the process exits.
        val runtime = lock.withLock { runtimes.remove(modelPath) }
        runtime?.close()
    }

    override suspend fun unloadAll() {
        val all = lock.withLock { runtimes.values.toList().also { runtimes.clear() } }
        all.forEach { it.close() }
    }

    private suspend fun newRuntime(modelPath: String): LlamaCppRuntime {
        val options = ModelOptions(modelPath = modelPath, accelerator = config.settings.accelerator)
        // mmap or not, loading touches the file and can take seconds on a large model.
        val model = withContext(Dispatchers.Default) { bridge.openModel(options) }
        return LlamaCppRuntime(
            bridge = bridge,
            modelOptions = options,
            systemPrompt = config.systemPrompt,
            model = model,
            nCtx = config.contextTokens,
            nThreads = config.threads,
            nPredict = config.maxOutputTokens,
            parameters = config.parameters,
            placementPolicy = placementPolicy,
        )
    }
}
