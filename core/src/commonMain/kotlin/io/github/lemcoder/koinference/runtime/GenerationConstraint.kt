package io.github.lemcoder.koinference.runtime

sealed interface GenerationConstraint {
    data class JsonSchema(val schema: String) : GenerationConstraint
}
