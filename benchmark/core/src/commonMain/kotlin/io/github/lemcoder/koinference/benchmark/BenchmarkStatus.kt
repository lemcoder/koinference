package io.github.lemcoder.koinference.benchmark

import kotlinx.serialization.Serializable

@Serializable
enum class BenchmarkStatus { SUCCESS, FAILED, SKIPPED }
