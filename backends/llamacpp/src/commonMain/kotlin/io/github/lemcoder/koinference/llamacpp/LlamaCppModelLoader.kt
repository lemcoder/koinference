package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.ModelLoader
import io.github.lemcoder.koinference.RuntimeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads GGUF models through llama.cpp.
 *
 * @param systemPrompt Applied to every generation this loader's runtimes perform.
 * @param settings     Backend selection; may be changed per runtime afterwards.
 * @param nCtx         Context size in tokens; 0 uses the model's trained size.
 * @param nThreads     CPU threads; 0 lets the facade pick.
 * @param nPredict     Maximum tokens to generate; 0 uses the facade's default.
 */
class LlamaCppModelLoader(
    private val systemPrompt: String? = null,
    private val settings: RuntimeSettings = RuntimeSettings(),
    private val nCtx: Int = 0,
    private val nThreads: Int = 0,
    private val nPredict: Int = 0,
) : ModelLoader {

    private val runtimes = mutableMapOf<String, LlamaCppRuntime>()

    override suspend fun load(modelPath: String): LlamaCppModelRuntime {
        require(modelPath.endsWith(".gguf")) {
            "llama.cpp loader expects a .gguf model path."
        }

        runtimes[modelPath]?.let { return it }

        // mmap or not, loading touches the file and can take seconds on a large model.
        val runtime = withContext(Dispatchers.Default) {
            LlamaCppRuntime.load(
                modelPath = modelPath,
                systemPrompt = systemPrompt,
                settings = settings,
                nCtx = nCtx,
                nThreads = nThreads,
                nPredict = nPredict,
            )
        }
        runtimes[modelPath] = runtime
        return runtime
    }

    override suspend fun unload(modelPath: String) {
        // Dropping the reference is not enough: the model and its session are native memory
        // and would live until the process exits.
        runtimes.remove(modelPath)?.close()
    }
}
