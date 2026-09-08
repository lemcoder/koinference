package io.github.lemcoder.koinference.onnx

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.runtime.Model
import io.github.lemcoder.koinference.onnx.internal.ModelFiles
import io.github.lemcoder.koinference.onnx.internal.OnnxBridge
import io.github.lemcoder.koinference.onnx.internal.OnnxModelOptions
import io.github.lemcoder.koinference.onnx.internal.PoolingMode
import io.github.lemcoder.koinference.onnx.internal.platformBridge
import io.github.lemcoder.koinference.onnx.internal.platformModelFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads ONNX encoders, with the vocabulary and pooling that belong to them.
 *
 * A `.onnx` file is a graph and nothing else: the vocabulary and the pooling mode live in files
 * beside it, the way ExecuTorch keeps its tokenizer out of the `.pte`. Both are found here so a
 * missing one fails in Kotlin, naming what it looked for.
 */
class OnnxModelLoader internal constructor(
    private val bridge: OnnxBridge,
    private val config: ModelConfig,
    private val files: ModelFiles = platformModelFiles(),
) : ModelLoader {

    constructor(config: ModelConfig = ModelConfig()) : this(platformBridge(), config)

    private val models = mutableMapOf<String, OnnxLoadedModel>()

    private val lock = Mutex()

    override suspend fun load(modelPath: String): Model {
        require(modelPath.endsWith(".onnx")) {
            "ONNX loader expects a .onnx model path, got: $modelPath"
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

    private suspend fun newModel(modelPath: String): OnnxLoadedModel {
        val directory = modelPath.substringBeforeLast('/', "")
        val vocabularyText = vocabularyBeside(directory)
            ?: error(
                "No vocab.txt for $modelPath. An ONNX graph carries no vocabulary; put the model's " +
                    "vocab.txt beside it or in its parent directory (looked in: " +
                    "${vocabularyCandidates(directory)})",
            )

        val vocabulary = parseVocabulary(vocabularyText)

        // sentence-transformers writes 1_Pooling/config.json beside the model; BGE pools the CLS
        // position and most others take the mean, and picking wrong costs retrieval quality without
        // failing.
        val pooling = PoolingMode.from(
            files.readText("$directory/1_Pooling/config.json")
                ?: files.readText("${directory.substringBeforeLast('/', "")}/1_Pooling/config.json"),
        )

        val model = withContext(Dispatchers.Default) {
            bridge.openModel(OnnxModelOptions(modelPath = modelPath, threads = config.threads))
        }

        return OnnxLoadedModel(
            model = model,
            vocabulary = vocabulary,
            poolingMode = pooling,
            maxTokens = config.contextTokens.takeIf { it > 0 } ?: DEFAULT_MAX_TOKENS,
            modelPath = modelPath,
        )
    }

    /** Beside the graph first, then one directory up: exports often sit in an `onnx/` subfolder. */
    private fun vocabularyBeside(directory: String): String? =
        vocabularyCandidates(directory).firstNotNullOfOrNull { files.readText(it) }

    private fun vocabularyCandidates(directory: String): List<String> = listOf(
        "$directory/vocab.txt",
        "${directory.substringBeforeLast('/', "")}/vocab.txt",
    )

    /** One token per line; the line number is the id, which is what `vocab.txt` means. */
    private fun parseVocabulary(text: String): Map<String, Int> = buildMap {
        text.lineSequence().forEachIndexed { index, line ->
            val token = line.trim()
            if (token.isNotEmpty()) putIfAbsent(token, index)
        }
    }

    private companion object {
        /** BERT's position limit, which every model this backend targets shares. */
        const val DEFAULT_MAX_TOKENS = 512
    }
}
