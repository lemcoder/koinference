package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.llamacpp.internal.CpuPlacementPolicy
import io.github.lemcoder.koinference.llamacpp.internal.parseCpuList
import io.github.lemcoder.koinference.llamacpp.internal.platformSystemFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The placement heuristic against a real machine.
 *
 * `CpuPlacementPolicyTest` covers the rule against topologies described by fake files, which is
 * where the branches are tested. What that cannot check is whether the files it expects exist and
 * say what it assumes on an actual Android device — whether `/proc/self/status` is readable from an
 * app sandbox, whether `cpuinfo_max_freq` is there per core, whether the numbers separate the
 * clusters at all. This runs on the device and checks exactly that.
 *
 * Deliberately not asserting a specific core list: this has to pass on whatever hardware it is
 * pointed at. It asserts the properties that must hold everywhere.
 */
class CpuPlacementDeviceTest {

    private val files = platformSystemFiles()

    @Test
    fun theFilesTheHeuristicNeedsAreReadable() {
        // An app sandbox can read these; if that ever stops being true the heuristic silently
        // degrades to no pinning, and this is the test that would notice.
        val status = files.read("/proc/self/status")
        assertTrue(status != null, "/proc/self/status unreadable")
        assertTrue(
            status!!.lineSequence().any { it.startsWith("Cpus_allowed_list:") },
            "no Cpus_allowed_list in /proc/self/status",
        )

        assertTrue(files.read("/sys/devices/system/cpu/online") != null, "online unreadable")
        assertTrue(
            files.read("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq") != null,
            "cpu0 has no cpufreq/cpuinfo_max_freq",
        )
    }

    @Test
    fun theChosenCoresAreOnesThisProcessMayUse() {
        val placement = CpuPlacementPolicy(files).choose()
        if (!placement.pinned) return  // A single-cluster device: nothing to assert.

        val permitted = files.read("/proc/self/status")!!
            .lineSequence()
            .first { it.startsWith("Cpus_allowed_list:") }
            .substringAfter(':')
            .let(::parseCpuList)
            .toSet()
        val online = parseCpuList(files.read("/sys/devices/system/cpu/online")!!).toSet()

        // The whole point of the intersection: pinning to a core outside the cpuset fails rather
        // than degrading, so a mask naming one is a bug and not a slow path.
        placement.cpus.forEach { cpu ->
            assertTrue(cpu in permitted, "chose cpu$cpu, not in the cpuset $permitted")
            assertTrue(cpu in online, "chose cpu$cpu, which is offline")
        }
        assertEquals(placement.cpus.size, placement.threads, "one worker per pinned core")
    }

    @Test
    fun theChosenCoresAreNotTheSlowestOnesAvailable() {
        val placement = CpuPlacementPolicy(files).choose()
        if (!placement.pinned) return

        val frequencies = placement.cpus.mapNotNull { cpu ->
            files.read("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")?.trim()?.toLongOrNull()
        }
        val slowestAnywhere = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
            files.read("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")?.trim()?.toLongOrNull()
        }.minOrNull()

        // A worker on a little core makes every barrier wait for it, so the chosen set must not
        // contain the slowest tier at all.
        assertTrue(frequencies.isNotEmpty(), "no frequencies for the chosen cores")
        assertTrue(
            slowestAnywhere == null || frequencies.all { it > slowestAnywhere },
            "chose cores at the slowest frequency $slowestAnywhere: $frequencies",
        )
    }

    @Test
    fun theChosenCoresAllRunAtTheSameSpeed() {
        val placement = CpuPlacementPolicy(files).choose()
        if (!placement.pinned) return

        // The set is meant to be cores that reach a barrier together. Mixing speeds inside it is
        // the failure the heuristic exists to avoid — including the lone prime core measured worse
        // than leaving it out.
        val distinct = placement.cpus.mapNotNull { cpu ->
            files.read("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")?.trim()
        }.distinct()
        assertEquals(1, distinct.size, "chose cores of differing speeds: $distinct")
    }
}
