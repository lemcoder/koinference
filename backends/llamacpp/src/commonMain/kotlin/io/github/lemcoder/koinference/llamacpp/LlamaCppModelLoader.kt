package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.ModelLoader
import io.github.lemcoder.koinference.RuntimeSettings
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
 * @param systemPrompt Applied to every generation this loader's runtimes perform.
 * @param settings     Backend the models start on; changeable per runtime afterwards, at the cost
 *                     of a reload.
 * @param parameters   Sampling defaults for the runtimes this loader returns.
 * @param nCtx         Context size in tokens; 0 uses the model's trained size.
 * @param nThreads     CPU threads; 0 lets the facade pick.
 * @param nPredict     Maximum tokens to generate; 0 uses the facade's default.
 */
class LlamaCppModelLoader internal constructor(
    private val bridge: LlamaCppBridge,
    private val systemPrompt: String?,
    private val settings: RuntimeSettings,
    private val parameters: GenerationParameters,
    private val nCtx: Int,
    private val nThreads: Int,
    private val nPredict: Int,
) : ModelLoader {

    constructor(
        systemPrompt: String? = null,
        settings: RuntimeSettings = RuntimeSettings(),
        parameters: GenerationParameters = GenerationParameters(),
        nCtx: Int = 0,
        nThreads: Int = 0,
        nPredict: Int = 0,
    ) : this(platformBridge(), systemPrompt, settings, parameters, nCtx, nThreads, nPredict)

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
        val options = ModelOptions(modelPath = modelPath, backend = settings.backend)
        // mmap or not, loading touches the file and can take seconds on a large model.
        val model = withContext(Dispatchers.Default) { bridge.openModel(options) }
        return LlamaCppRuntime(
            bridge = bridge,
            modelOptions = options,
            systemPrompt = systemPrompt,
            model = model,
            nCtx = nCtx,
            nThreads = nThreads,
            nPredict = nPredict,
            parameters = parameters,
        )
    }
}
