package io.github.lemcoder.koinference.benchmark.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ResponseFormat(
    val type: String,
    @SerialName("json_schema") val jsonSchema: JsonSchemaSpec? = null,
)
