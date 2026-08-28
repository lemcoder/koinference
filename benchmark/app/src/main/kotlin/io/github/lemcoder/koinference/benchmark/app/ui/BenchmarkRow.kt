package io.github.lemcoder.koinference.benchmark.app.ui

/** One line of the results table: an engine against a workload. */
data class BenchmarkRow(
    val engineId: String,
    val workload: String,
    val status: String,
    val tokensPerSecond: Double?,
    val ttftMs: Double?,
    val tokens: Int?,
    val chunks: Int?,
    val peakPssMb: Double?,
    val note: String?,
)
