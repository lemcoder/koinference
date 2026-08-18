@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.litertlm.internal

import koinference_litertlm.KOILM_BACKEND_CPU
import koinference_litertlm.KOILM_BACKEND_GPU
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Keeps the hand-written backend ids in step with the facade header.
 *
 * The JNI leg cannot import the generated constants, so it repeats their values; this is the
 * only place both the generated and the repeated ones are visible at once.
 */
class BackendIdTest {

    @Test
    fun backendIdsMatchTheHeader() {
        // The generated constants are UInt; the hand-written ones are the Int the JNI
        // bridge marshals. Compared as Int, which is what both eventually reach the C API as.
        assertEquals(KOILM_BACKEND_CPU.toInt(), BackendId.CPU)
        assertEquals(KOILM_BACKEND_GPU.toInt(), BackendId.GPU)
    }
}
