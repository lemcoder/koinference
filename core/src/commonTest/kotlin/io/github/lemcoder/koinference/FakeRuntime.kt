package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.GenerationConstraint
import io.github.lemcoder.koinference.runtime.GenerationParameters
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import io.github.lemcoder.koinference.runtime.TextModelRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal class FakeRuntime(val modelPath: String, config: ModelConfig) : TextModelRuntime {

    override var generationParameters: GenerationParameters = config.parameters
        private set

    override var runtimeSettings: RuntimeSettings = config.settings
        private set

    override suspend fun generateResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
    ): String = "reply from $modelPath"

    override fun streamResponse(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
    ): Flow<String> = flowOf("reply ", "from ", modelPath)

    override suspend fun countTokens(text: String): Int = text.split(" ").count { it.isNotBlank() }

    override suspend fun updateGenerationParameters(parameters: GenerationParameters) {
        generationParameters = parameters
    }

    override suspend fun updateRuntimeSettings(settings: RuntimeSettings) {
        runtimeSettings = settings
    }
}
