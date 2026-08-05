package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.GenerationConstraint
import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.RuntimeSettings
import io.github.lemcoder.koinference.llamacpp.gguf.GgufMetadata
import io.github.lemcoder.koinference.llamacpp.gguf.GgufParser
import io.github.lemcoder.koinference.llamacpp.gguf.readFileBytes

class LlamaCppRuntime(private val modelPath: String) : LlamaCppTextRuntime {
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

    suspend fun readGgufMetadata(): GgufMetadata = GgufParser.parse(readFileBytes(modelPath))
}