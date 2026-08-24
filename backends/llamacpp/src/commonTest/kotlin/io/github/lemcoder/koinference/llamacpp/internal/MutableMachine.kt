package io.github.lemcoder.koinference.llamacpp.internal

/**
 * A Pixel 8a whose cpuset can change while a test is running.
 *
 * The device this repo's numbers come from: 4x A510 @ 1.70 GHz, 4x A715 @ 2.37 GHz, 1x X3 @
 * 2.91 GHz. [permitted] is the interesting part — Android moves an app between cpusets, and that
 * is what makes a mask chosen earlier wrong later.
 */
internal class MutableMachine(var permitted: String = "0-8") : SystemFiles {

    private val frequencies = buildMap {
        (0..3).forEach { put(it, 1_704_000L) }
        (4..7).forEach { put(it, 2_367_000L) }
        put(8, 2_914_000L)
    }

    override fun read(path: String): String? = when {
        path == "/proc/self/status" -> "Cpus_allowed_list:\t$permitted\n"
        path == "/sys/devices/system/cpu/online" -> "0-8"
        path.endsWith("/cpufreq/cpuinfo_max_freq") ->
            frequencies[path.removePrefix("/sys/devices/system/cpu/cpu").substringBefore('/').toInt()]
                ?.toString()

        else -> null
    }
}
