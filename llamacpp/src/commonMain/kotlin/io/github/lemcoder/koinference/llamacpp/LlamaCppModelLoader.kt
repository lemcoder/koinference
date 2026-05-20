package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.GenerationConstraint
import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.ModelLoader
import io.github.lemcoder.koinference.ModelRuntime
import io.github.lemcoder.koinference.RuntimeSettings

class LlamaCppModelLoader : ModelLoader {
    private val runtimes = mutableMapOf<String, LlamaCppRuntime>()

    override suspend fun load(modelPath: String): ModelRuntime {
        require(modelPath.endsWith(".gguf")) {
            "llama.cpp loader expects a .gguf model path."
        }

        return runtimes.getOrPut(modelPath) { LlamaCppRuntime(modelPath) }
    }

    override suspend fun unload(modelPath: String) {
        runtimes.remove(modelPath)
    }
}

class LlamaCppRuntime(private val modelPath: String) : ModelRuntime {
    var generationParameters: GenerationParameters = GenerationParameters()
        private set

    var runtimeSettings: RuntimeSettings = RuntimeSettings()
        private set

    override suspend fun generateResponse(
        prompt: String,
        constraint: GenerationConstraint?,
    ): String {
        val schemaSuffix = when (constraint) {
            is GenerationConstraint.JsonSchema -> " with schema constraints"
            null -> ""
        }

        return "Stub llama.cpp response for \"$prompt\" from $modelPath$schemaSuffix"
    }

    override fun updateGenerationParameters(parameters: GenerationParameters) {
        generationParameters = parameters
    }

    override fun updateRuntimeSettings(settings: RuntimeSettings) {
        runtimeSettings = settings
    }
}
