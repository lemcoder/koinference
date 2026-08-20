package io.github.lemcoder.koinference.benchmark

import kotlinx.serialization.Serializable

@Serializable
data class ThermalMetrics(
    val batteryTemperatureBeforeC: Double? = null,
    val batteryTemperatureAfterC: Double? = null,
    val batteryTemperaturePeakC: Double? = null,
    /** PowerManager.getCurrentThermalStatus(), as its constant name. Null before API 29. */
    val thermalStatusBefore: String? = null,
    val thermalStatusAfter: String? = null,
    val thermalStatusPeak: String? = null,
    /** Samples taken during the run, oldest first. Empty when nothing could be read. */
    val samples: List<ThermalSample> = emptyList(),
)
