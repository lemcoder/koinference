package io.github.lemcoder.koinference

sealed interface GenerationConstraint {
    data class JsonSchema(val schema: String) : GenerationConstraint
}
