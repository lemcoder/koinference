package io.github.lemcoder.koinference.benchmark

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.io.File

internal object AndroidProbe : PlatformProbe {

    override fun monotonicNanos(): Long = SystemClock.elapsedRealtimeNanos()

    override fun processUptimeMs(): Double? {
        // Process.getStartElapsedRealtime() is on the same clock as elapsedRealtime(), so the
        // difference is genuine process startup rather than a timer the harness started late.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        return (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()).toDouble()
    }

    override fun describeDevice(): DeviceInfo {
        val context = BenchmarkContext.applicationContext
        val activityManager = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = activityManager?.let {
            ActivityManager.MemoryInfo().also { info -> it.getMemoryInfo(info) }
        }

        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            androidVersion = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.firstOrNull(),
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            // SOC_MANUFACTURER/SOC_MODEL are the only first-party SoC identifiers Android has,
            // and they arrived in API 31. Below that the marketing model name is all there is,
            // which is exactly why it is not trusted on its own.
            socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null,
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
            hardware = Build.HARDWARE,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            cpuMaxFrequenciesKhz = readCpuMaxFrequencies(),
            ramMb = memoryInfo?.totalMem?.let { it / (1024 * 1024) },
            isEmulator = isEmulator(),
        )
    }

    override fun readMemory(): MemorySnapshot {
        // Debug.MemoryInfo is the process's own accounting and needs no permission. PSS is the
        // number to compare engines on: llama.cpp's weights are native allocations that never
        // appear in the Java heap.
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)

        val runtime = Runtime.getRuntime()
        return MemorySnapshot(
            pssKb = info.totalPss.toLong(),
            // VmRSS from /proc/self/status: readable for a process's own status without root.
            rssKb = readProcStatusKb("VmRSS"),
            nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024,
            javaHeapKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024,
        )
    }

    override fun readThermal(): ThermalSample {
        val context = BenchmarkContext.applicationContext
        val battery = batteryIntent(context)

        return ThermalSample(
            atMs = 0.0, // stamped by the caller, which knows where in the run this sits
            batteryTemperatureC = battery
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }
                // Reported in tenths of a degree.
                ?.let { it / 10.0 },
            thermalStatus = readThermalStatus(context),
            cpuFrequenciesKhz = readCpuCurrentFrequencies(),
        )
    }

    override fun readBattery(): BatteryReading {
        val context = BenchmarkContext.applicationContext
        val intent = batteryIntent(context)
        val manager = context?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 }
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)?.takeIf { it > 0 }
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

        return BatteryReading(
            percent = if (level != null && scale != null) level * 100 / scale else null,
            charging = status?.let {
                it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
            },
            // Most devices do not implement this counter and return Long.MIN_VALUE or 0. Both
            // are rejected: a fabricated energy figure is worse than no energy figure.
            energyCounterNwh = manager
                ?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
                ?.takeIf { it != Long.MIN_VALUE && it != 0L },
        )
    }

    private fun readThermalStatus(context: Context?): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val power = context?.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return when (power.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> null
        }
    }

    @Suppress("DEPRECATION") // registerReceiver(null, filter) is the documented sticky-intent read
    private fun batteryIntent(context: Context?): Intent? = context?.registerReceiver(
        null,
        IntentFilter(Intent.ACTION_BATTERY_CHANGED),
    )

    private fun readProcStatusKb(key: String): Long? = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("$key:") }
                ?.substringAfter(':')
                ?.trim()
                ?.removeSuffix(" kB")
                ?.trim()
                ?.toLongOrNull()
        }
    }.getOrNull()

    // cpufreq is world-readable on most devices and unreadable on some. Unreadable means an
    // empty list, not a zero.
    private fun readCpuCurrentFrequencies(): List<Long> = readCpuFrequencies("scaling_cur_freq")

    private fun readCpuMaxFrequencies(): List<Long> = readCpuFrequencies("cpuinfo_max_freq")

    private fun readCpuFrequencies(file: String): List<Long> = runCatching {
        (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
            File("/sys/devices/system/cpu/cpu$cpu/cpufreq/$file")
                .takeIf { it.canRead() }
                ?.readText()
                ?.trim()
                ?.toLongOrNull()
        }
    }.getOrDefault(emptyList())

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu")
}
