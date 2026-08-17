package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.ModelLoader
import io.github.lemcoder.koinference.RuntimeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // Held across the load, not only around the map: two callers asking for the same model
    // would otherwise both load the weights, and the one that lost the race would be dropped
    // from the map with no way left to free it.
    private val lock = Mutex()

    override suspend fun load(modelPath: String): LlamaCppModelRuntime {
        require(modelPath.endsWith(".gguf")) {
            "llama.cpp loader expects a .gguf model path."
        }

        return lock.withLock {
            runtimes[modelPath] ?: newRuntime(modelPath).also { runtimes[modelPath] = it }
        }
    }

    override suspend fun unload(modelPath: String) {
        // Dropping the reference is not enough: the model and its session are native memory
        // and would live until the process exits.
        val runtime = lock.withLock { runtimes.remove(modelPath) }
        runtime?.close()
    }

    override suspend fun unloadAll() {
        val all = lock.withLock { runtimes.values.toList().also { runtimes.clear() } }
        all.forEach { it.close() }
    }

    // mmap or not, loading touches the file and can take seconds on a large model.
    private suspend fun newRuntime(modelPath: String): LlamaCppRuntime =
        withContext(Dispatchers.Default) {
            LlamaCppRuntime.load(
                modelPath = modelPath,
                systemPrompt = systemPrompt,
                settings = settings,
                nCtx = nCtx,
                nThreads = nThreads,
                nPredict = nPredict,
            )
        }
}
