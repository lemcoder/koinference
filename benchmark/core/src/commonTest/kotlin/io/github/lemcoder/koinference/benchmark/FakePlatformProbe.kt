package io.github.lemcoder.koinference.benchmark

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A probe whose clock only moves when a test says so.
 *
 * Every reading is null, which is also what the real host probe reports: a metric the platform
 * cannot produce is absent, never zero.
 */
internal class FakePlatformProbe(
    private var nanos: Long = 1_000_000_000L,
    private val memory: MemorySnapshot? = null,
    private val thermal: ThermalSample? = null,
    private val battery: BatteryReading? = null,
) : PlatformProbe {

    fun advance(by: Long) {
        nanos += by
    }

    override fun monotonicNanos(): Long = nanos

    override fun describeDevice(): DeviceInfo = DeviceInfo()

    override fun readMemory(): MemorySnapshot? = memory

    override fun readThermal(): ThermalSample? = thermal

    override fun readBattery(): BatteryReading? = battery

    override fun processUptimeMs(): Double? = null
}
