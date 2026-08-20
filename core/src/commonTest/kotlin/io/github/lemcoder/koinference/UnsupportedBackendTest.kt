package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.backend.BackendUnsupportedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * An engine the device cannot run must fail before it touches anything native.
 *
 * The failure being prevented is not an exception: ggml's ARM kernels are chosen at compile time,
 * so a CPU without dot-product support takes SIGILL partway through a decode and the application
 * sees a process death. Nothing can catch that, which is why the check happens up here, in Kotlin,
 * before the loader is asked for anything.
 */
class UnsupportedBackendTest {

    private val reason = "the CPU has no ARM dot-product extension (asimddp)"

    @Test
    fun loadRefusesAnUnrunnableBackendBeforeReadingWeights() = runTest {
        val backend = FakeBackend("llama.cpp", listOf(".gguf"), unsupportedReason = reason)
        val koi = Koinference(backend)

        val failure = assertFailsWith<BackendUnsupportedException> { koi.load("/m/a.gguf") }

        assertEquals("llama.cpp", failure.backendId)
        assertEquals(reason, failure.reason)
        assertTrue("llama.cpp" in failure.message.orEmpty(), failure.message.orEmpty())
        // No loader was ever created: the refusal comes before the weights, not from them.
        assertTrue(backend.loaders.isEmpty(), "an unrunnable backend must not open a model")
    }

    @Test
    fun theOtherBackendsStillWork() = runTest {
        // Registering an engine this device cannot run is not itself an error — an application ships
        // both and may only ever load the container the working one reads.
        val llama = FakeBackend("llama.cpp", listOf(".gguf"), unsupportedReason = reason)
        val other = FakeBackend("litert-lm", listOf(".litertlm"))
        val koi = Koinference(llama, other)

        koi.load("/m/a.litertlm")

        assertEquals(1, other.loaders.size)
    }

    @Test
    fun unsupportedNamesEveryBackendThatCannotRun() {
        val llama = FakeBackend("llama.cpp", listOf(".gguf"), unsupportedReason = reason)
        val other = FakeBackend("litert-lm", listOf(".litertlm"))

        // What an application reads at startup to hide a feature, instead of catching a throw later.
        assertEquals(mapOf("llama.cpp" to reason), Koinference(llama, other).unsupported)
        assertEquals(emptyMap(), Koinference(other).unsupported)
    }
}
