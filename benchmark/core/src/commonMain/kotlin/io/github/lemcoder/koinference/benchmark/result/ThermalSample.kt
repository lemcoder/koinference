package io.github.lemcoder.koinference.benchmark.result

import kotlinx.serialization.Serializable

@Serializable
data class ThermalSample(
    val atMs: Double,
    val batteryTemperatureC: Double? = null,
    val thermalStatus: String? = null,
    /** Per-core scaling frequency in kHz, when /sys is readable without root. */
    val cpuFrequenciesKhz: List<Long> = emptyList(),
)
