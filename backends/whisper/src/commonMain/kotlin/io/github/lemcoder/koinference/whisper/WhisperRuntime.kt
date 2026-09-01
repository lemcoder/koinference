package io.github.lemcoder.koinference.whisper

import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.Accelerator
import io.github.lemcoder.koinference.runtime.GenerationConstraint
import io.github.lemcoder.koinference.runtime.GenerationParameters
import io.github.lemcoder.koinference.runtime.ResponsePart
import io.github.lemcoder.koinference.runtime.RuntimeGuard
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import io.github.lemcoder.koinference.whisper.internal.AudioBytes
import io.github.lemcoder.koinference.whisper.internal.WavAudio
import io.github.lemcoder.koinference.whisper.internal.WhisperBridge
import io.github.lemcoder.koinference.whisper.internal.WhisperModel
import io.github.lemcoder.koinference.whisper.internal.WhisperModelOptions
import io.github.lemcoder.koinference.whisper.internal.WhisperTranscriptionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * A loaded whisper model, transcribing whatever audio a prompt carries.
 *
 * State behind one [RuntimeGuard], like every other backend: the model is native memory, and
 * freeing it while a transcription runs is a use-after-free rather than an exception.
 */
class WhisperRuntime internal constructor(
    private val bridge: WhisperBridge,
    private var modelOptions: WhisperModelOptions,
    private var model: WhisperModel,
    private val audio: AudioBytes,
    private var transcriptionOptions: WhisperTranscriptionOptions,
    parameters: GenerationParameters = GenerationParameters(),
) : WhisperTextRuntime {

    override var generationParameters: GenerationParameters = parameters
        private set

    override val runtimeSettings: RuntimeSettings
        get() = RuntimeSettings(if (modelOptions.useGpu) Accelerator.GPU else Accelerator.CPU)

    private val guard = RuntimeGuard { modelOptions.modelPath }

    override suspend fun generateResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
    ): List<ResponsePart> {
        val samples = samples(prompt)
        refuse(constraint)

        return guard.whileOpen {
            withContext(Dispatchers.Default) {
                listOf(ResponsePart.Text(model.transcribe(samples, transcriptionOptions)))
            }
        }
    }

    override fun streamResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
    ): Flow<ResponsePart> {
        val samples = samples(prompt)
        return guard.streamWhileOpen {
            refuse(constraint)
            // One segment per emission: whisper's unit of output is a segment, not a token, which
            // is why the harness's chunk count is not a token count for this engine either.
            emitAll(model.stream(samples, transcriptionOptions).map(ResponsePart::Text))
        }
    }

    /**
     * Only the thread count survives; whisper has no sampler to retune.
     *
     * Kept rather than refused because a caller retuning a whole registry should not have to know
     * which engine ignores what — [Whisper.honours] is where that is said.
     */
    override suspend fun updateGenerationParameters(parameters: GenerationParameters) {
        guard.whileOpen { generationParameters = parameters }
    }

    /** Where whisper runs is fixed when the weights are loaded, so this reloads them. */
    override suspend fun updateRuntimeSettings(settings: RuntimeSettings) {
        guard.whileOpen {
            val wantsGpu = settings.accelerator == Accelerator.GPU
            if (wantsGpu == modelOptions.useGpu) return@whileOpen

            val options = modelOptions.copy(useGpu = wantsGpu)
            model.close()
            model = withContext(Dispatchers.Default) { bridge.openModel(options) }
            modelOptions = options
        }
    }

    internal suspend fun close() = guard.close { model.close() }

    /**
     * The audio a prompt carries, as samples.
     *
     * Text in a prompt is refused rather than ignored: whisper takes audio, and a caller who sent a
     * question instead of a recording has made a mistake worth hearing about. Several parts are
     * concatenated, which is what a caller splitting a long recording would expect.
     */
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
        decoded.forEach { part ->
            part.copyInto(joined, at)
            at += part.size
        }
        return joined
    }

    /** No grammar and no schema: whisper constrains nothing. */
    private fun refuse(constraint: GenerationConstraint?) {
        if (constraint != null) error("whisper.cpp exposes no constrained decoding")
    }
}
