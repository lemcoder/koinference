package io.github.lemcoder.koinference.llamacpp.internal

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Exercises the generated JNI bridges against the real stub library, so a regression in the
 * generator's marshalling shows up as a failing test rather than at first inference.
 */
class LlamaCppBridgeJvmSmokeTest {

    @Test
    fun `backend reports system info`() {
        llamaBackendInit()
        try {
            val info = llamaSystemInfo()
            assertTrue(info.isNotEmpty(), "expected a non-empty system info string")
            assertTrue(info.contains("="), "expected feature flags, got: $info")
        } finally {
            llamaBackendFree()
        }
    }

    @Test
    fun `loading a missing model returns a null handle`() {
        assertTrue(llamaModelLoad("/nonexistent/model.gguf") == 0L)
    }
}
