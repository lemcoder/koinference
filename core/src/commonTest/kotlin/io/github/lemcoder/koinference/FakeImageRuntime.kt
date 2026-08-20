package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.GenerationParameters
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import io.github.lemcoder.koinference.runtime.vision.GeneratedImage
import io.github.lemcoder.koinference.runtime.vision.ImageFormat
import io.github.lemcoder.koinference.runtime.vision.ImageModelRuntime

internal class FakeImageRuntime(config: ModelConfig) : ImageModelRuntime {

    override var generationParameters: GenerationParameters = config.parameters
        private set

    override var runtimeSettings: RuntimeSettings = config.settings
        private set

    /** Rejects a part it cannot use, the way the text runtimes do. */
    override suspend fun generateImage(prompt: List<PromptPart>): GeneratedImage {
        val unusable = prompt.filterNot { it is PromptPart.Text || it is PromptPart.ImageFile }
        if (unusable.isNotEmpty()) {
            throw UnsupportedOperationException("fake-diffusion cannot handle $unusable")
        }
        return GeneratedImage(byteArrayOf(1, 2, 3), ImageFormat.PNG, width = 8, height = 8)
    }

    override suspend fun updateGenerationParameters(parameters: GenerationParameters) {
        generationParameters = parameters
    }

    override suspend fun updateRuntimeSettings(settings: RuntimeSettings) {
        runtimeSettings = settings
    }
}
