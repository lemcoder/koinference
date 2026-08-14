package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.ModelLoader
import io.github.lemcoder.koinference.RuntimeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads LiteRT-LM models.
 *
 * @param cacheDir   Writable directory LiteRT-LM may use to speed up subsequent loads of
 *                   the same model; null leaves the runtime to its own default.
 * @param systemPrompt Applied to every conversation this loader opens.
 * @param settings   Backend selection; may be changed per runtime afterwards.
 * @param nThreads   CPU threads; 0 leaves the engine default.
 * @param maxTokens  Engine-wide token budget; 0 uses the model's own.
 */
class LiteRtLmModelLoader(
    private val cacheDir: String? = null,
    private val systemPrompt: String? = null,
    private val settings: RuntimeSettings = RuntimeSettings(),
    private val nThreads: Int = 0,
    private val maxTokens: Int = 0,
) : ModelLoader {

    private val runtimes = mutableMapOf<String, LiteRtLmRuntime>()

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

        runtimes[modelPath]?.let { return it }

        // Loading maps and prepares the weights, so it does not belong on the caller's
        // thread even though the handle it returns is just a pointer.
        val runtime = withContext(Dispatchers.Default) {
            LiteRtLmRuntime.load(
                modelPath = modelPath,
                cacheDir = cacheDir,
                systemPrompt = systemPrompt,
                settings = settings,
                nThreads = nThreads,
                maxTokens = maxTokens,
            )
        }
        runtimes[modelPath] = runtime
        return runtime
    }

    override suspend fun unload(modelPath: String) {
        // Unlike the llama.cpp loader, dropping the reference is not enough: the engine is
        // native memory and would leak until the process exits.
        runtimes.remove(modelPath)?.close()
    }
}
