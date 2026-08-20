package io.github.lemcoder.koinference.benchmark.app.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorBody(
    val message: String,
    val type: String,
    val code: String? = null,
)
