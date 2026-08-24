package io.github.lemcoder.koinference.llamacpp.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A machine described by the files Linux would describe it with.
 *
 * @param freqs peak kHz per CPU index.
 * @param parts `CPU part` per CPU index, for SoCs whose frequencies do not separate the clusters.
 * @param permitted what this process may use — the cpuset, not the hardware.
 */
internal class FakeMachine(
    private val freqs: Map<Int, Long> = emptyMap(),
    private val parts: Map<Int, String> = emptyMap(),
    private val permitted: String? = null,
    private val online: String? = null,
) : SystemFiles {

    override fun read(path: String): String? = when {
        path == "/proc/self/status" ->
            permitted?.let { "Name:\tapp\nCpus_allowed_list:\t$it\nThreads:\t9\n" }

        path == "/sys/devices/system/cpu/online" -> online

        path == "/proc/cpuinfo" -> parts.takeIf { it.isNotEmpty() }?.entries
            ?.joinToString("\n") { (cpu, part) -> "processor\t: $cpu\nCPU part\t: $part" }

        path.endsWith("/cpufreq/cpuinfo_max_freq") -> {
            val cpu = path.removePrefix("/sys/devices/system/cpu/cpu").substringBefore('/').toInt()
            freqs[cpu]?.toString()
        }

        else -> null
    }
}
