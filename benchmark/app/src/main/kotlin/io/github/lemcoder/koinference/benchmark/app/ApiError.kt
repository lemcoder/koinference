package io.github.lemcoder.koinference.benchmark.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiError(val error: ApiErrorBody)
