@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.llamacpp.internal.platformCpuPlacement
import platform.posix._SC_NPROCESSORS_ONLN
import platform.posix.sysconf
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
    fun pinningIsDeclined() {
        val placement = platformCpuPlacement().choose()

        assertFalse(placement.pinned, "expected no pinning on Darwin, got ${placement.cpus}")
    }

    @Test
    fun theWorkerCountIsCoresMinusTwo() {
        val placement = platformCpuPlacement().choose()

        // The count is this leg's whole contribution, since it cannot pin. Measured on an M4:
        // 8 threads gave 144 tok/s against 120 for 5 and 135 for 9.
        val cores = sysconf(_SC_NPROCESSORS_ONLN).toInt()
        assertEquals((cores - 2).coerceIn(2, 8), placement.threads)
    }
}
