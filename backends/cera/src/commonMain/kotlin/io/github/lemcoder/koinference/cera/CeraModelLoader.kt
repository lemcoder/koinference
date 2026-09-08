package io.github.lemcoder.koinference.cera

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.runtime.Model
import io.github.lemcoder.koinference.cera.internal.CeraBridge
import io.github.lemcoder.koinference.cera.internal.CeraModelOptions
import io.github.lemcoder.koinference.cera.internal.CeraSessionOptions
import io.github.lemcoder.koinference.cera.internal.platformBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads GGUF models through Cera.
 *
 * [ModelConfig.threads] is ignored: Cera picks its own worker count, and there is nothing in the
 * bindings to override it with.
 */
class CeraModelLoader internal constructor(
    private val bridge: CeraBridge,
    private val config: ModelConfig,
) : ModelLoader {

    constructor(config: ModelConfig = ModelConfig()) : this(platformBridge(), config)

    private val models = mutableMapOf<String, CeraLoadedModel>()

    // Held across the load, not only around the map: two callers asking for the same model would
    // otherwise both load the weights, and the loser would be dropped with no way left to free it.
    private val lock = Mutex()

    override suspend fun load(modelPath: String): Model {
        require(modelPath.endsWith(".gguf")) {
            "Cera loader expects a .gguf model path, got: $modelPath"
        }

        return lock.withLock {
            models[modelPath] ?: newModel(modelPath).also { models[modelPath] = it }
        }
    }

    override suspend fun unload(modelPath: String) {
        // Dropping the reference is not enough: the engine is Rust-side memory that would live
        // until the process exits.
        val model = lock.withLock { models.remove(modelPath) }
        model?.close()
    }

    override suspend fun unloadAll() {
        val all = lock.withLock { models.values.toList().also { models.clear() } }
        all.forEach { it.close() }
    }

    // The one place that knows both vocabularies: ModelConfig's and Cera's.
    private suspend fun newModel(modelPath: String): CeraLoadedModel {
        val modelOptions = CeraModelOptions(
            modelPath = modelPath,
            accelerator = config.settings.accelerator,
            contextTokens = config.contextTokens,
        )

        val model = withContext(Dispatchers.Default) { bridge.openModel(modelOptions) }

        return CeraLoadedModel(
            modelOptions = modelOptions,
            model = model,
            sessionOptions = CeraSessionOptions(
                systemPrompt = config.systemPrompt,
                maxOutputTokens = config.maxOutputTokens,
                contextTokens = config.contextTokens,
                temperature = config.parameters.temperature,
                topK = config.parameters.topK,
                topP = config.parameters.topP,
                minP = config.parameters.minP,
                seed = config.parameters.seed,
            ),
            parameters = config.parameters,
        )
    }
}
