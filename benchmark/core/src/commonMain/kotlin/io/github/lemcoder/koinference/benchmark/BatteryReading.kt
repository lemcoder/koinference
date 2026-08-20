package io.github.lemcoder.koinference.benchmark

data class BatteryReading(
    val percent: Int? = null,
    val charging: Boolean? = null,
    val energyCounterNwh: Long? = null,
)
