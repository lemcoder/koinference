package io.github.lemcoder.koinference.llamacpp.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CpuPlacementShapeTest {

    @Test
    fun unpinnedStillCarriesAWorkerCount() {
        // The shape every non-Linux platform returns: nothing to pin to, but a measured count.
        // Darwin's is cores - 2, and it has to survive being called "unpinned".
        val placement = CpuPlacement.unpinned(threads = 8)

        assertFalse(placement.pinned)
        assertEquals(8, placement.threads)
    }

    @Test
    fun theNoOpinionCaseIsDistinctFromAMeasuredOne() {
        // 0 means "even the count is the facade's problem", which is a different answer from 8.
        assertEquals(0, CpuPlacement.UNPINNED.threads)
        assertFalse(CpuPlacement.UNPINNED.pinned)
    }
}
