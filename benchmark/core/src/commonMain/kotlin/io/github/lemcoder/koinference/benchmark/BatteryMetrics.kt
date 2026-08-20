package io.github.lemcoder.koinference.benchmark

import kotlinx.serialization.Serializable

/**
 * Battery, deliberately coarse.
 *
 * A percentage delta is not energy. On most devices the level moves in whole percent, so a
 * short run reads zero regardless of what it drew, and Firebase Test Lab devices are mains
 * powered besides. These fields exist to be honest about that, and the shape leaves room for
 * a real energy counter later.
 */
@Serializable
data class BatteryMetrics(
    val percentBefore: Int? = null,
    val percentAfter: Int? = null,
    val percentDelta: Int? = null,
    val charging: Boolean? = null,
    /** BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER, nanowatt-hours, when the device has it. */
    val energyCounterNwhBefore: Long? = null,
    val energyCounterNwhAfter: Long? = null,
)
