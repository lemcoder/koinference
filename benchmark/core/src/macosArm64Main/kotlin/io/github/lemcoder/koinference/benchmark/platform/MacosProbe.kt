@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.benchmark.platform

import io.github.lemcoder.koinference.benchmark.result.DeviceInfo
import io.github.lemcoder.koinference.benchmark.result.ThermalSample
import kotlin.time.TimeSource

internal object MacosProbe : PlatformProbe {

    // Relative to the first call rather than to an epoch, which is all a duration needs.
    private val origin = TimeSource.Monotonic.markNow()

    override fun monotonicNanos(): Long = origin.elapsedNow().inWholeNanoseconds

    override fun describeDevice(): DeviceInfo = DeviceInfo(
        hostPlatform = "macosArm64",
        cpuCores = null,
        isEmulator = false,
    )

    // Comparable process memory on macOS would mean mach task_info, whose numbers are not the
    // same thing as Android's PSS. Two metrics with the same name and different meanings in
    // one results set is worse than one missing metric.
    override fun readMemory(): MemorySnapshot? = null

    override fun readThermal(): ThermalSample? = null

    override fun readBattery(): BatteryReading? = null

    override fun processUptimeMs(): Double? = null
}
