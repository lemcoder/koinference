package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.AudioFormat
import io.github.lemcoder.koinference.runtime.GeneratingRuntime
import io.github.lemcoder.koinference.runtime.GenerationConstraint
import io.github.lemcoder.koinference.runtime.GenerationParameters
import io.github.lemcoder.koinference.runtime.ResponsePart
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Answers with speech and its transcript, interleaved the way an omni model does. */
internal class FakeOmniRuntime(config: ModelConfig) : GeneratingRuntime {

    override var generationParameters: GenerationParameters = config.parameters
        private set

    override var runtimeSettings: RuntimeSettings = config.settings
        private set

    override suspend fun generateResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
    ): List<ResponsePart> = reply()

    override fun streamResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
    ): Flow<ResponsePart> = flowOf(*reply().toTypedArray())

    private fun reply() = listOf(
        ResponsePart.Text("Hello"),
        ResponsePart.Audio(byteArrayOf(1, 2), AudioFormat.PCM_16, sampleRateHz = 24_000),
        ResponsePart.Text(" there"),
        ResponsePart.Audio(byteArrayOf(3, 4), AudioFormat.PCM_16, sampleRateHz = 24_000),
    )

    override suspend fun updateGenerationParameters(parameters: GenerationParameters) {
        generationParameters = parameters
    }

    override suspend fun updateRuntimeSettings(settings: RuntimeSettings) {
        runtimeSettings = settings
    }
}
