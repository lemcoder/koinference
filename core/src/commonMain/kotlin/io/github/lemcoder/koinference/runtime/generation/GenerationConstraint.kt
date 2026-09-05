package io.github.lemcoder.koinference.runtime.generation

sealed interface GenerationConstraint {
    data class JsonSchema(val schema: String) : GenerationConstraint
}
