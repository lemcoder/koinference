package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.GeneratingConnection
import io.github.lemcoder.koinference.runtime.KoinferenceException
import io.github.lemcoder.koinference.runtime.generation.GenerationConstraint
import io.github.lemcoder.koinference.runtime.generation.GenerationParameters
import io.github.lemcoder.koinference.runtime.media.AudioFormat
import io.github.lemcoder.koinference.runtime.media.ResponsePart

/** Answers with speech and its transcript, interleaved the way an omni model does. */
internal class FakeOmniConnection(
    parameters: GenerationParameters,
) : GeneratingConnection {

    private var closed = false

    override var generationParameters: GenerationParameters = parameters
        private set

    override suspend fun generate(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
        onPart: (ResponsePart) -> Unit,
    ) {
        if (closed) throw KoinferenceException.ConnectionClosed("fake-omni")
        listOf(
            ResponsePart.Text("Hello"),
            ResponsePart.Audio(byteArrayOf(1, 2), AudioFormat.PCM_16, sampleRateHz = 24_000),
            ResponsePart.Text(" there"),
            ResponsePart.Audio(byteArrayOf(3, 4), AudioFormat.PCM_16, sampleRateHz = 24_000),
        ).forEach(onPart)
    }

    override suspend fun updateGenerationParameters(parameters: GenerationParameters) {
        generationParameters = parameters
    }

    override suspend fun close() { closed = true }
}
