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
private class FakeMachine(
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

private fun tiers(vararg sizes: Pair<Long, Int>): Map<Int, Long> {
    val out = mutableMapOf<Int, Long>()
    var cpu = 0
    sizes.forEach { (khz, count) -> repeat(count) { out[cpu++] = khz } }
    return out
}

class CpuPlacementPolicyTest {

    /** 4x A510 @ 1.70, 4x A715 @ 2.37, 1x X3 @ 2.91 — the device every number here came from. */
    private val pixel8a = tiers(1_704_000L to 4, 2_367_000L to 4, 2_914_000L to 1)

    @Test
    fun picksTheA715ClusterOnAPixel8a() {
        val placement = CpuPlacementPolicy(
            FakeMachine(freqs = pixel8a, permitted = "0-8", online = "0-8"),
        ).choose()

        // Not the prime core, and not all five big cores: one core 23% faster than four others
        // still waits at the same barrier, and including it measured slower.
        assertEquals(listOf(4, 5, 6, 7), placement.cpus)
        assertEquals(4, placement.threads)
    }

    @Test
    fun aBackgroundedAppPinsInsideItsCpuset() {
        // /dev/cpuset/background on this phone is the little cluster. Pinning to cores the process
        // may not touch fails rather than degrading, so the mask has to live inside the cpuset.
        val placement = CpuPlacementPolicy(
            FakeMachine(freqs = pixel8a, permitted = "0-3", online = "0-8"),
        ).choose()

        // Every usable core is now in one frequency group, so there is no faster group to prefer.
        assertFalse(placement.pinned)
    }

    @Test
    fun aForegroundAppWithoutThePrimeCoreStillPicksTheBigCluster() {
        // /dev/cpuset/foreground is 0-7 here: the prime core is excluded already.
        val placement = CpuPlacementPolicy(
            FakeMachine(freqs = pixel8a, permitted = "0-7", online = "0-8"),
        ).choose()

        assertEquals(listOf(4, 5, 6, 7), placement.cpus)
    }

    @Test
    fun offlineCoresAreNotPinnedTo() {
        // Thermal pressure can take cores offline; a pinned worker on one that vanished is worse
        // than an unpinned one.
        val placement = CpuPlacementPolicy(
            FakeMachine(freqs = pixel8a, permitted = "0-8", online = "0-5,8"),
        ).choose()

        assertEquals(listOf(4, 5), placement.cpus)
        assertEquals(2, placement.threads)
    }

    @Test
    fun aThreeClusterSocPrefersTheWidestClusterNotTheFastest() {
        // 1x prime + 3x big + 4x little, the Snapdragon 8 Gen 1 shape. The widest group above the
        // little cluster is the three big cores.
        val placement = CpuPlacementPolicy(
            FakeMachine(
                freqs = tiers(1_785_000L to 4, 2_500_000L to 3, 3_000_000L to 1),
                permitted = "0-7",
                online = "0-7",
            ),
        ).choose()

        assertEquals(listOf(4, 5, 6), placement.cpus)
        assertEquals(3, placement.threads)
    }

    @Test
    fun anEvenlySplitSocTakesTheFasterHalf() {
        val placement = CpuPlacementPolicy(
            FakeMachine(freqs = tiers(1_800_000L to 4, 2_400_000L to 4), permitted = "0-7", online = "0-7"),
        ).choose()

        assertEquals(listOf(4, 5, 6, 7), placement.cpus)
    }

    @Test
    fun oneKindOfCoreIsNotPinned() {
        // Nothing to avoid, so pinning would only constrain the scheduler for no reason.
        val placement = CpuPlacementPolicy(
            FakeMachine(freqs = tiers(2_400_000L to 8), permitted = "0-7", online = "0-7"),
        ).choose()

        assertFalse(placement.pinned)
        assertEquals(0, placement.threads)
    }

    @Test
    fun microarchitectureBreaksAFrequencyTie() {
        // Some SoCs clock a big and a little core to the same ceiling, which makes frequency
        // useless as a discriminator. A715 (0xd4d) sorts above A510 (0xd46).
        val placement = CpuPlacementPolicy(
            FakeMachine(
                freqs = tiers(2_000_000L to 8),
                parts = mapOf(
                    0 to "0xd46", 1 to "0xd46", 2 to "0xd46", 3 to "0xd46",
                    4 to "0xd4d", 5 to "0xd4d", 6 to "0xd4d", 7 to "0xd4d",
                ),
                permitted = "0-7",
                online = "0-7",
            ),
        ).choose()

        assertEquals(listOf(4, 5, 6, 7), placement.cpus)
    }

    @Test
    fun aPlatformWithNoTopologyFilesIsNotPinned() {
        // macOS, iOS, or a Linux without cpufreq. Nothing to read, nothing to decide.
        assertFalse(CpuPlacementPolicy(FakeMachine()).choose().pinned)
    }

    @Test
    fun aSingleUsableCoreIsNotPinned() {
        val placement = CpuPlacementPolicy(
            FakeMachine(freqs = pixel8a, permitted = "4", online = "0-8"),
        ).choose()

        assertFalse(placement.pinned)
    }

    @Test
    fun anAbsentCpusetFallsBackToWhatIsOnline() {
        // No /proc/self/status — assume every online core is fair game rather than refusing to pin.
        val placement = CpuPlacementPolicy(
            FakeMachine(freqs = pixel8a, permitted = null, online = "0-8"),
        ).choose()

        assertEquals(listOf(4, 5, 6, 7), placement.cpus)
    }
}

class CpuListParsingTest {

    @Test
    fun parsesRangesAndSingletons() {
        assertEquals(listOf(0, 1, 2, 3), parseCpuList("0-3"))
        assertEquals(listOf(0, 1, 2, 3, 5), parseCpuList("0-3,5"))
        assertEquals(listOf(4), parseCpuList("4"))
        assertEquals(listOf(0, 1, 8, 9), parseCpuList(" 0-1 , 8-9 "))
    }

    @Test
    fun toleratesRubbishRatherThanThrowing() {
        // These files are kernel-generated, but a parse failure here must not take down a load.
        assertTrue(parseCpuList("").isEmpty())
        assertTrue(parseCpuList("garbage").isEmpty())
        assertTrue(parseCpuList("5-2").isEmpty())
    }
}
