package io.github.lemcoder.koinference.benchmark.app.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JsonSchemaSpec(
    val name: String? = null,
    val strict: Boolean? = null,
    /** The schema itself, passed through to the backend's constrained decoding as written. */
    val schema: kotlinx.serialization.json.JsonElement? = null,
)
