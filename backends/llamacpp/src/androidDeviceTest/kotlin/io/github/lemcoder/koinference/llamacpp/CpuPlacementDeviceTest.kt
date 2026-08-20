package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.llamacpp.internal.parseCpuList
import io.github.lemcoder.koinference.llamacpp.internal.platformCpuPlacement
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The placement heuristic against a real machine.
 *
 * `CpuPlacementPolicyTest` covers the rule against topologies described by fake files, which is
 * where the branches live. What that cannot check is whether the files it expects exist and say
 * what it assumes on an actual device — whether an app sandbox may read `/proc/self/status`,
 * whether every core reports `cpuinfo_max_freq`, whether the numbers separate the clusters at all.
 * This runs on the device and checks exactly that.
 *
 * Deliberately not asserting a particular core list: it has to pass on whatever hardware it is
 * pointed at, so it asserts the properties that must hold everywhere. Reads the files directly
 * rather than through the policy's own seam — a test that used the same reader as the code under
 * test could not tell a missing file from a misparsed one.
 */
class CpuPlacementDeviceTest {

    private fun read(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    private fun maxFrequency(cpu: Int): Long? =
        read("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")?.trim()?.toLongOrNull()

    @Test
    fun theFilesTheHeuristicNeedsAreReadable() {
        // An app sandbox can read these. If that ever stops being true the heuristic silently
        // degrades to no pinning — a 2.5x throughput loss with no error — and this is the test that
        // would notice.
        val status = read("/proc/self/status")
        assertTrue(status != null, "/proc/self/status unreadable from an app")
        assertTrue(
            status!!.lineSequence().any { it.startsWith("Cpus_allowed_list:") },
            "no Cpus_allowed_list in /proc/self/status",
        )
        assertTrue(read("/sys/devices/system/cpu/online") != null, "cpu/online unreadable")
        assertTrue(maxFrequency(0) != null, "cpu0 reports no cpuinfo_max_freq")
    }

    @Test
    fun theHeuristicPinsSomewhereOnThisDevice() {
        // A phone has clusters. If this device has them and we still decline to pin, the rule is
        // failing to read a topology it should be able to see.
        val placement = platformCpuPlacement().choose()
        val distinctFrequencies = (0 until Runtime.getRuntime().availableProcessors())
            .mapNotNull(::maxFrequency)
            .distinct()

        if (distinctFrequencies.size < 2) return  // Genuinely single-cluster: nothing to choose.
        assertTrue(placement.pinned, "device has ${distinctFrequencies.size} tiers but chose no mask")
    }

    @Test
    fun theChosenCoresAreOnesThisProcessMayUse() {
        val placement = platformCpuPlacement().choose()
        if (!placement.pinned) return

        val permitted = read("/proc/self/status")!!
            .lineSequence()
            .first { it.startsWith("Cpus_allowed_list:") }
            .substringAfter(':')
            .let(::parseCpuList)
            .toSet()
        val online = parseCpuList(read("/sys/devices/system/cpu/online")!!).toSet()

        // The point of the intersection: pinning to a core outside the cpuset fails rather than
        // degrading, so a mask naming one is a bug and not a slow path.
        placement.cpus.forEach { cpu ->
            assertTrue(cpu in permitted, "chose cpu$cpu, outside the cpuset $permitted")
            assertTrue(cpu in online, "chose cpu$cpu, which is offline")
        }
        assertEquals(placement.cpus.size, placement.threads, "one worker per pinned core")
    }

    @Test
    fun theChosenCoresAreNotTheSlowestAvailable() {
        val placement = platformCpuPlacement().choose()
        if (!placement.pinned) return

        val slowest = (0 until Runtime.getRuntime().availableProcessors())
            .mapNotNull(::maxFrequency)
            .minOrNull()
        val chosen = placement.cpus.mapNotNull(::maxFrequency)

        // A worker on a little core makes every barrier wait for it, so the slowest tier must not
        // appear in the chosen set at all.
        assertTrue(chosen.isNotEmpty(), "no frequencies for the chosen cores")
        assertTrue(
            slowest == null || chosen.all { it > slowest },
            "chose cores at the slowest frequency $slowest: $chosen",
        )
    }

    @Test
    fun theChosenCoresAllRunAtTheSameSpeed() {
        val placement = platformCpuPlacement().choose()
        if (!placement.pinned) return

        // The set is meant to be cores that reach a barrier together. Mixing speeds inside it is
        // the failure the rule exists to avoid: including this phone's lone prime core, 23% faster
        // than the four beside it, measured slower than leaving it out.
        assertEquals(
            1,
            placement.cpus.mapNotNull(::maxFrequency).distinct().size,
            "chose cores of differing speeds: ${placement.cpus.map { it to maxFrequency(it) }}",
        )
    }
}
