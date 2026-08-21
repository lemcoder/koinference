package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.backend.BackendUnsupportedException
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.llamacpp.internal.FakeLlamaCppBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * A device that cannot run this engine is refused before anything native is called.
 *
 * See `docs/backends.md` for why waiting for it to fail on its own is not an option.
 */
class LlamaCppUnsupportedDeviceTest {

    private val bridge = FakeLlamaCppBridge()
    private val reason = "the CPU has no ARM dot-product extension (asimddp)"

    @Test
    fun `load refuses an unrunnable device before opening the model`() = runTest {
        val loader = LlamaCppModelLoader(bridge, ModelConfig(), unsupportedReason = { reason })

        val failure = assertFailsWith<BackendUnsupportedException> { loader.load("/models/a.gguf") }

        assertEquals(LlamaCpp.id, failure.backendId)
        assertEquals(reason, failure.reason)
        assertTrue(reason in failure.message.orEmpty(), failure.message.orEmpty())
        assertTrue(bridge.models.isEmpty(), "the weights must not be touched")
    }

    @Test
    fun `a supported device loads normally`() = runTest {
        val loader = LlamaCppModelLoader(bridge, ModelConfig(), unsupportedReason = { null })

        loader.load("/models/a.gguf")

        assertEquals(1, bridge.models.size)
    }
}
