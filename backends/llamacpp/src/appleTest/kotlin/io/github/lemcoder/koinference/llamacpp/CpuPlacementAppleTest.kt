package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.llamacpp.internal.platformCpuPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Placement on Darwin, where there is none.
 *
 * macOS and iOS have neither `/proc` nor `/sys` to read a topology from, and no equivalent of
 * `sched_setaffinity` — `thread_policy_set` affinity tags are advisory and ignored on Apple
 * silicon — so there is nothing to pin to. The native leg says so outright rather than running the
 * Android rule and getting an empty answer because the files happen to be absent. This holds it to
 * that, so the heuristic cannot start half-working on a platform it was never meant for.
 */
class CpuPlacementAppleTest {

    @Test
    fun placementIsLeftToTheOs() {
        val placement = platformCpuPlacement().choose()

        assertFalse(placement.pinned, "expected no pinning on Darwin, got ${placement.cpus}")
        // Zero, not a guess: the facade's own fallback decides the worker count.
        assertEquals(0, placement.threads)
    }
}
