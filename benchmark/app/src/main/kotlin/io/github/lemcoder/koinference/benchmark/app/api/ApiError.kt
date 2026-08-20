package io.github.lemcoder.koinference.benchmark.app.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiError(val error: ApiErrorBody)
