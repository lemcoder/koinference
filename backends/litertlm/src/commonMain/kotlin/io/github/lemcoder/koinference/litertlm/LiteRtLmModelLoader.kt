package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.ModelLoader
import io.github.lemcoder.koinference.RuntimeSettings
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
 * @param cacheDir     Writable directory LiteRT-LM may use to speed up subsequent loads of
 *                     the same model; null leaves the runtime to its own default.
 * @param systemPrompt Applied to every conversation this loader opens.
 * @param settings     Backend the models start on; changeable per runtime afterwards, at the
 *                     cost of a reload.
 * @param parameters   Sampling defaults for the runtimes this loader returns.
 * @param nThreads     CPU threads; 0 leaves the engine default.
 * @param maxTokens    Engine-wide token budget; 0 uses the model's own.
 */
class LiteRtLmModelLoader internal constructor(
    private val bridge: LiteRtLmBridge,
    private val cacheDir: String?,
    private val systemPrompt: String?,
    private val settings: RuntimeSettings,
    private val parameters: GenerationParameters,
    private val nThreads: Int,
    private val maxTokens: Int,
) : ModelLoader {

    constructor(
        cacheDir: String? = null,
        systemPrompt: String? = null,
        settings: RuntimeSettings = RuntimeSettings(),
        parameters: GenerationParameters = GenerationParameters(),
        nThreads: Int = 0,
        maxTokens: Int = 0,
    ) : this(platformBridge(), cacheDir, systemPrompt, settings, parameters, nThreads, maxTokens)

    private val runtimes = mutableMapOf<String, LiteRtLmRuntime>()

    // Held across the load itself, not only around the map. Two callers asking for the same
    // model would otherwise both miss the cache and both load the weights, and the one that
    // lost the race would be dropped from the map with no way left to free it. Loading a
    // second, different model has to wait — the alternative is a per-path lock, which is more
    // machinery than a loader that is normally used from one place needs.
    private val lock = Mutex()

    // Narrowed to the text runtime, not the sealed parent: LiteRT-LM has no embedding runtime,
    // so returning the union would only force every caller into a downcast that can never
    // fail. :backends:llamacpp cannot do this — which of its two runtimes you get depends on
    // the model.
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
            cacheDir = cacheDir,
            backend = settings.backend,
            nThreads = nThreads,
            maxTokens = maxTokens,
        )
        // Loading maps and prepares the weights, so it does not belong on the caller's
        // thread even though the handle it returns is just a pointer.
        val engine = withContext(Dispatchers.Default) { bridge.openEngine(options) }
        return LiteRtLmRuntime(
            bridge = bridge,
            engineOptions = options,
            systemPrompt = systemPrompt,
            engine = engine,
            parameters = parameters,
        )
    }
}
