package io.github.lemcoder.koinference.whisper

import io.github.lemcoder.koinference.runtime.Connection
import io.github.lemcoder.koinference.runtime.Model
import io.github.lemcoder.koinference.runtime.generation.GenerationParameters
import io.github.lemcoder.koinference.whisper.internal.AudioBytes
import io.github.lemcoder.koinference.whisper.internal.WhisperModel
import io.github.lemcoder.koinference.whisper.internal.WhisperModelOptions
import io.github.lemcoder.koinference.whisper.internal.WhisperTranscriptionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A loaded whisper model: the weights, and a factory for transcription connections. */
class WhisperLoadedModel internal constructor(
    private val modelOptions: WhisperModelOptions,
    private val model: WhisperModel,
    private val audio: AudioBytes,
    private val transcriptionOptions: WhisperTranscriptionOptions,
    private val parameters: GenerationParameters,
) : Model {

    private var closed = false

    override suspend fun open(): Connection =
        WhisperGeneratingConnection(model, modelOptions.modelPath, audio, transcriptionOptions, parameters)

    override suspend fun close() {
        if (closed) return
        closed = true
        withContext(Dispatchers.Default) { model.close() }
    }
}
