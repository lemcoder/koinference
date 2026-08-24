package io.github.lemcoder.koinference.benchmark.result

import kotlinx.serialization.Serializable

@Serializable
enum class BenchmarkStatus { SUCCESS, FAILED, SKIPPED }
