package io.github.lemcoder.koinference.executorch

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.executorch.internal.ExecuTorchBridge
import io.github.lemcoder.koinference.executorch.internal.ExecuTorchModelOptions
import io.github.lemcoder.koinference.executorch.internal.ExecuTorchSessionOptions
import io.github.lemcoder.koinference.executorch.internal.SystemFiles
import io.github.lemcoder.koinference.executorch.internal.TokenizerFile
import io.github.lemcoder.koinference.executorch.internal.platformBridge
import io.github.lemcoder.koinference.executorch.internal.platformFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads `.pte` programs through ExecuTorch.
 *
 * [ModelConfig.threads] and [ModelConfig.cacheDir] are ignored: the binding exposes neither.
 */
class ExecuTorchModelLoader internal constructor(
    private val bridge: ExecuTorchBridge,
    private val config: ModelConfig,
    private val files: SystemFiles = platformFiles(),
) : ModelLoader {

    constructor(config: ModelConfig = ModelConfig()) : this(platformBridge(), config)

    private val runtimes = mutableMapOf<String, ExecuTorchRuntime>()

    private val lock = Mutex()

    override suspend fun load(modelPath: String): ExecuTorchTextRuntime {
        require(modelPath.endsWith(".pte")) {
            "ExecuTorch loader expects a .pte model path, got: $modelPath"
        }

        return lock.withLock {
            runtimes[modelPath] ?: newRuntime(modelPath).also { runtimes[modelPath] = it }
        }
    }

    override suspend fun unload(modelPath: String) {
        val runtime = lock.withLock { runtimes.remove(modelPath) }
        runtime?.close()
    }

    override suspend fun unloadAll() {
        val all = lock.withLock { runtimes.values.toList().also { runtimes.clear() } }
        all.forEach { it.close() }
    }

    private suspend fun newRuntime(modelPath: String): ExecuTorchRuntime {
        // Found here rather than inside the bridge, so the failure is a Kotlin exception naming the
        // files it looked for. Handing LlmModule a missing tokenizer path crashes in native code.
        val tokenizerPath = TokenizerFile.beside(modelPath, files)
            ?: error(
                "No tokenizer beside $modelPath. ExecuTorch keeps the vocabulary out of the .pte; " +
                    "put one of these next to it: ${TokenizerFile.searched(modelPath)}",
            )

        val temperature = config.parameters.temperature ?: DEFAULT_TEMPERATURE
        val modelOptions = ExecuTorchModelOptions(
            modelPath = modelPath,
            tokenizerPath = tokenizerPath,
            // Fixed at construction by LlmModule, not per generation, which is why it is here.
            temperature = temperature,
        )

        val model = withContext(Dispatchers.Default) { bridge.openModel(modelOptions) }

        return ExecuTorchRuntime(
            bridge = bridge,
            modelOptions = modelOptions,
            model = model,
            sessionOptions = ExecuTorchSessionOptions(
                maxOutputTokens = config.maxOutputTokens,
                contextTokens = config.contextTokens,
                temperature = temperature,
            ),
            parameters = config.parameters,
        )
    }

    private companion object {
        /** What ExecuTorch's own examples use; `GenerationParameters` leaves it null. */
        const val DEFAULT_TEMPERATURE = 0.8
    }
}
