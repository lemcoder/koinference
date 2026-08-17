@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.benchmark

import kotlin.time.TimeSource

/**
 * The host probe: timing, and almost nothing else.
 *
 * This target exists so the harness itself can be exercised where both engines really run. It
 * is not a device, and it does not pretend to be one — memory, thermal and battery all return
 * null, which is the same thing an Android device reports when a reading is genuinely
 * unavailable. If these returned plausible host numbers instead, a macOS run would produce a
 * file that looked like a phone measurement.
 */
actual fun platformProbe(): PlatformProbe = MacosProbe

private object MacosProbe : PlatformProbe {

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
