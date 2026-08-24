package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.GeneratingRuntime
import io.github.lemcoder.koinference.runtime.GenerationConstraint
import io.github.lemcoder.koinference.runtime.GenerationParameters
import io.github.lemcoder.koinference.runtime.ResponsePart
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A text-only engine: every part it emits is [ResponsePart.Text]. */
internal class FakeRuntime(val modelPath: String, config: ModelConfig) : GeneratingRuntime {

    override var generationParameters: GenerationParameters = config.parameters
        private set

    override var runtimeSettings: RuntimeSettings = config.settings
        private set

    override suspend fun generateResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
    ): List<ResponsePart> = listOf(ResponsePart.Text("reply from $modelPath"))

    override fun streamResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
    ): Flow<ResponsePart> = flowOf(
        ResponsePart.Text("reply "),
        ResponsePart.Text("from "),
        ResponsePart.Text(modelPath),
    )

    override suspend fun updateGenerationParameters(parameters: GenerationParameters) {
        generationParameters = parameters
    }

    override suspend fun updateRuntimeSettings(settings: RuntimeSettings) {
        runtimeSettings = settings
    }
}
