package io.github.lemcoder.koinference.whisper

import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.ConnectionGuard
import io.github.lemcoder.koinference.runtime.GeneratingConnection
import io.github.lemcoder.koinference.runtime.generation.GenerationConstraint
import io.github.lemcoder.koinference.runtime.generation.GenerationParameters
import io.github.lemcoder.koinference.runtime.media.ResponsePart
import io.github.lemcoder.koinference.whisper.internal.AudioBytes
import io.github.lemcoder.koinference.whisper.internal.WavAudio
import io.github.lemcoder.koinference.whisper.internal.WhisperModel
import io.github.lemcoder.koinference.whisper.internal.WhisperTranscriptionOptions
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map

/**
 * A transcription connection over a loaded whisper model.
 *
 * whisper_full carries nothing between calls, so there is no per-connection state to reset and
 * several connections share the model freely. The weights belong to [WhisperLoadedModel].
 */
class WhisperGeneratingConnection internal constructor(
    private val model: WhisperModel,
    private val target: String,
    private val audio: AudioBytes,
    private val transcriptionOptions: WhisperTranscriptionOptions,
    parameters: GenerationParameters,
) : GeneratingConnection {

    override var generationParameters: GenerationParameters = parameters
        private set

    private val guard = ConnectionGuard { target }

    override val isClosed: Boolean get() = guard.isClosed

    override suspend fun generate(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
        onPart: (ResponsePart) -> Unit,
    ) {
        val samples = samples(prompt)
        refuse(constraint)
        guard.whileOpen {
            // One segment per emission; whisper's unit of output is a segment, not a token.
            model.stream(samples, transcriptionOptions).map(ResponsePart::Text).collect { onPart(it) }
        }
    }

    override suspend fun updateGenerationParameters(parameters: GenerationParameters) {
        guard.whileOpen { generationParameters = parameters }
    }

    override suspend fun close() = guard.close { }

    private fun samples(prompt: List<PromptPart>): FloatArray {
        require(prompt.isNotEmpty()) { "whisper needs audio to transcribe; the prompt was empty" }
        val decoded = prompt.map { part ->
            when (part) {
                is PromptPart.AudioFile -> WavAudio.decode(audio.read(part.path))
                is PromptPart.AudioBytes -> WavAudio.decode(part.bytes)
                else -> error(
                    "whisper transcribes audio; got ${part::class.simpleName}. Use " +
                        "PromptPart.AudioFile or PromptPart.AudioBytes.",
                )
            }
        }
        if (decoded.size == 1) return decoded.single()
        val total = decoded.sumOf { it.size }
        val joined = FloatArray(total)
        var at = 0
        decoded.forEach { part -> part.copyInto(joined, at); at += part.size }
        return joined
    }

    private fun refuse(constraint: GenerationConstraint?) {
        if (constraint != null) error("whisper.cpp exposes no constrained decoding")
    }
}
