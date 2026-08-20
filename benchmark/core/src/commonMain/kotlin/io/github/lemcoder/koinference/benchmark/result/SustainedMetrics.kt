package io.github.lemcoder.koinference.benchmark.result

import kotlinx.serialization.Serializable

@Serializable
data class SustainedMetrics(
    val requestedSeconds: Int,
    val actualSeconds: Double,
    val iterations: Int,
    /** Chunks/sec of each iteration in order, so a decline under load is visible. */
    val chunksPerSecondSeries: List<Double> = emptyList(),
    val wallClockMsSeries: List<Double> = emptyList(),
    val thermalSamples: List<ThermalSample> = emptyList(),
)
