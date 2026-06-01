package io.github.lemcoder.koinference

interface ModelRuntime

data class GenerationParameters(
    val topK: Int? = null,
    val minP: Double? = null,
)

enum class InferenceBackend {
    CPU,
    GPU,
}

data class RuntimeSettings(
    val backend: InferenceBackend = InferenceBackend.CPU,
)

sealed interface GenerationConstraint {
    data class JsonSchema(val schema: String) : GenerationConstraint
}
