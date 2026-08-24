package io.github.lemcoder.koinference.benchmark.platform

import io.github.lemcoder.koinference.benchmark.result.DeviceInfo
import io.github.lemcoder.koinference.benchmark.result.ThermalSample

/**
 * Everything the harness needs from the platform it is running on.
 *
 * Every reading is nullable and the host implementation returns null for most of them. That is
 * the contract: a metric this platform cannot produce is reported as absent, never as zero and
 * never as an estimate. The result schema and the analysis tool are built around that.
 */
interface PlatformProbe {

    /**
     * Monotonic nanoseconds. On Android this is `SystemClock.elapsedRealtimeNanos()`, which
     * keeps counting through deep sleep and cannot be dragged by a clock adjustment.
     */
    fun monotonicNanos(): Long

    fun describeDevice(): DeviceInfo

    /** A memory reading taken now, or null where the platform exposes nothing comparable. */
    fun readMemory(): MemorySnapshot?

    fun readThermal(): ThermalSample?

    fun readBattery(): BatteryReading?

    /**
     * Milliseconds from process start to now, when the platform can tell. This is the only
     * honest way to report startup: a timer started inside the harness has already missed it.
     */
    fun processUptimeMs(): Double?
}
