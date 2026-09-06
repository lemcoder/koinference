package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.runtime.Model
import io.github.lemcoder.koinference.llamacpp.internal.CpuPlacementSource
import io.github.lemcoder.koinference.llamacpp.internal.platformCpuPlacement
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
    private val placementPolicy: CpuPlacementSource = platformCpuPlacement(),
) : ModelLoader {

    constructor(config: ModelConfig = ModelConfig()) : this(platformBridge(), config)

    private val models = mutableMapOf<String, LlamaCppLoadedModel>()

    // Held across the load, not only around the map: two callers asking for the same model would
    // otherwise both load the weights, and the one that lost the race would be dropped with no way
    // left to free it.
    private val lock = Mutex()

    override suspend fun load(modelPath: String): Model {
        require(modelPath.endsWith(".gguf")) {
            "llama.cpp loader expects a .gguf model path, got: $modelPath"
        }
        return lock.withLock {
            models[modelPath] ?: newModel(modelPath).also { models[modelPath] = it }
        }
    }

    override suspend fun unload(modelPath: String) {
        val model = lock.withLock { models.remove(modelPath) }
        model?.close()
    }

    override suspend fun unloadAll() {
        val all = lock.withLock { models.values.toList().also { models.clear() } }
        all.forEach { it.close() }
    }

    private suspend fun newModel(modelPath: String): LlamaCppLoadedModel {
        val options = ModelOptions(modelPath = modelPath, accelerator = config.settings.accelerator)
        val model = withContext(Dispatchers.Default) { bridge.openModel(options) }
        return LlamaCppLoadedModel(
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
