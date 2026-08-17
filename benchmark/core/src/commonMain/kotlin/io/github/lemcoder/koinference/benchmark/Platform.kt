package io.github.lemcoder.koinference.benchmark

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

/** One memory reading. Fields the platform does not expose stay null. */
data class MemorySnapshot(
    val pssKb: Long? = null,
    val rssKb: Long? = null,
    val nativeHeapKb: Long? = null,
    val javaHeapKb: Long? = null,
)

data class BatteryReading(
    val percent: Int? = null,
    val charging: Boolean? = null,
    val energyCounterNwh: Long? = null,
)

/** The probe for the target this was compiled for. */
expect fun platformProbe(): PlatformProbe
