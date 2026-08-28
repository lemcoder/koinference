package io.github.lemcoder.koinference.cera

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
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

    private val runtimes = mutableMapOf<String, CeraRuntime>()

    // Held across the load, not only around the map: two callers asking for the same model would
    // otherwise both load the weights, and the loser would be dropped with no way left to free it.
    private val lock = Mutex()

    override suspend fun load(modelPath: String): CeraTextRuntime {
        require(modelPath.endsWith(".gguf")) {
            "Cera loader expects a .gguf model path, got: $modelPath"
        }

        return lock.withLock {
            runtimes[modelPath] ?: newRuntime(modelPath).also { runtimes[modelPath] = it }
        }
    }

    override suspend fun unload(modelPath: String) {
        // Dropping the reference is not enough: the engine is Rust-side memory that would live
        // until the process exits.
        val runtime = lock.withLock { runtimes.remove(modelPath) }
        runtime?.close()
    }

    override suspend fun unloadAll() {
        val all = lock.withLock { runtimes.values.toList().also { runtimes.clear() } }
        all.forEach { it.close() }
    }

    // The one place that knows both vocabularies: ModelConfig's and Cera's.
    private suspend fun newRuntime(modelPath: String): CeraRuntime {
        val modelOptions = CeraModelOptions(
            modelPath = modelPath,
            accelerator = config.settings.accelerator,
            contextTokens = config.contextTokens,
        )

        val model = withContext(Dispatchers.Default) { bridge.openModel(modelOptions) }

        return CeraRuntime(
            bridge = bridge,
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
