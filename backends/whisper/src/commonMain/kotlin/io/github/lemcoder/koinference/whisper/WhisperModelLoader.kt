package io.github.lemcoder.koinference.whisper

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.runtime.Model
import io.github.lemcoder.koinference.runtime.generation.Accelerator
import io.github.lemcoder.koinference.whisper.internal.AudioBytes
import io.github.lemcoder.koinference.whisper.internal.WhisperBridge
import io.github.lemcoder.koinference.whisper.internal.WhisperModelOptions
import io.github.lemcoder.koinference.whisper.internal.WhisperTranscriptionOptions
import io.github.lemcoder.koinference.whisper.internal.platformAudioBytes
import io.github.lemcoder.koinference.whisper.internal.platformBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads whisper.cpp models.
 *
 * [ModelConfig.maxOutputTokens] and [ModelConfig.contextTokens] are ignored: a transcript is as long
 * as the audio, not as long as a budget.
 */
class WhisperModelLoader internal constructor(
    private val bridge: WhisperBridge,
    private val config: ModelConfig,
    private val audio: AudioBytes = platformAudioBytes(),
) : ModelLoader {

    constructor(config: ModelConfig = ModelConfig()) : this(platformBridge(), config)

    private val models = mutableMapOf<String, WhisperLoadedModel>()

    private val lock = Mutex()

    override suspend fun load(modelPath: String): Model {
        require(Whisper.handles(modelPath)) {
            "whisper loader expects a ggml-*.bin model path, got: $modelPath"
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

    private suspend fun newModel(modelPath: String): WhisperLoadedModel {
        val modelOptions = WhisperModelOptions(
            modelPath = modelPath,
            useGpu = config.settings.accelerator == Accelerator.GPU,
        )

        val model = withContext(Dispatchers.Default) { bridge.openModel(modelOptions) }

        return WhisperLoadedModel(
            modelOptions = modelOptions,
            model = model,
            audio = audio,
            transcriptionOptions = WhisperTranscriptionOptions(
                // Null lets whisper detect the language, which is what its own examples default to.
                language = config.systemPrompt?.takeIf { it.length <= LANGUAGE_CODE_LENGTH },
                threads = config.threads,
            ),
            parameters = config.parameters,
        )
    }

    private companion object {
        /**
         * `ModelConfig` has no language field, and one engine's need is not reason enough to add one
         * to `:core`. A short `systemPrompt` is read as an ISO code — "en", "pl" — and anything
         * longer is left alone so a caller who set a real system prompt is not silently reinterpreted.
         */
        const val LANGUAGE_CODE_LENGTH = 5
    }
}
