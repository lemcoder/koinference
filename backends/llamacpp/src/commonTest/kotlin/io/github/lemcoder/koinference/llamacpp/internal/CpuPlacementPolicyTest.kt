package io.github.lemcoder.koinference.llamacpp.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun tiers(vararg sizes: Pair<Long, Int>): Map<Int, Long> {
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
