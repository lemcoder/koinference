@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.lemcoder.koinference.litertlm.internal

import koinference_litertlm.koilm_default_session_params
import kotlinx.cinterop.useContents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The defaults exist twice — once in Kotlin, because Android's SamplerConfig demands concrete
 * numbers, and once in the facade. Two copies drift silently; this is what makes them not.
 */
class SessionDefaultsTest {

    @Test
    fun theFacadeAgreesWithTheCommonDefaults() {
        koilm_default_session_params().useContents {
            assertEquals(DEFAULT_TOP_K, top_k)
            assertEquals(DEFAULT_TOP_P, top_p)
            assertEquals(DEFAULT_TEMPERATURE, temp)
            assertTrue(seed < 0, "the facade should leave LiteRT-LM's own seeding, got $seed")
        }
    }
}
