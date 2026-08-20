package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.backend.ModelConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KoinferenceTest {

    private val gguf = FakeBackend("llama.cpp", listOf(".gguf"))
    private val litertlm = FakeBackend("litert-lm", listOf(".litertlm", ".task"))
    private val koi = Koinference(gguf, litertlm)

    @Test
    fun loadsThroughWhicheverBackendReadsTheContainer() = runTest {
        assertEquals("reply from /m/a.gguf", koi.load("/m/a.gguf").generateResponse("hi"))
        assertEquals("reply from /m/b.task", koi.load("/m/b.task").generateResponse("hi"))

        // Each backend was asked for exactly one loader, and only when it was needed.
        assertEquals(1, gguf.loaders.size)
        assertEquals(1, litertlm.loaders.size)
    }

    @Test
    fun theSamePathIsLoadedOnce() = runTest {
        // Weights are the expensive part; two calls must not read them twice.
        assertSame(koi.load("/m/a.gguf"), koi.load("/m/a.gguf"))
    }

    @Test
    fun anUnreadableContainerNamesWhatIsRegistered() = runTest {
        // The usual cause is a module that was not depended on, which "unsupported model" hides.
        val failure = assertFailsWith<IllegalStateException> { koi.load("/m/model.onnx") }

        assertTrue(failure.message!!.contains("llama.cpp"), failure.message!!)
        assertTrue(failure.message!!.contains("litert-lm"), failure.message!!)
    }

    @Test
    fun unloadReachesTheLoaderThatLoaded() = runTest {
        koi.load("/m/a.gguf")

        koi.unload("/m/a.gguf")

        // A second loader would not know about the runtime the first handed out.
        assertEquals(listOf("/m/a.gguf"), gguf.loaders.single().unloaded)
    }

    @Test
    fun unloadAllReachesEveryBackend() = runTest {
        koi.load("/m/a.gguf")
        koi.load("/m/b.litertlm")

        koi.unloadAll()

        assertEquals(listOf("/m/a.gguf"), gguf.loaders.single().unloaded)
        assertEquals(listOf("/m/b.litertlm"), litertlm.loaders.single().unloaded)
    }

    @Test
    fun theInstanceStaysUsableAfterUnloadAll() = runTest {
        koi.load("/m/a.gguf")
        koi.unloadAll()

        assertEquals("reply from /m/a.gguf", koi.load("/m/a.gguf").generateResponse("hi"))
    }

    @Test
    fun theConfigReachesEveryModelThisInstanceLoads() = runTest {
        val configured = Koinference(listOf(gguf), ModelConfig(contextTokens = 512))

        configured.load("/m/a.gguf")

        assertEquals(512, gguf.loaders.single().config.contextTokens)
    }

    @Test
    fun backendsCanBeInspectedWithoutLoading() {
        assertEquals(listOf("llama.cpp", "litert-lm"), koi.backendIds)
        assertEquals(gguf, koi.backendFor("/m/a.gguf"))
        assertEquals(litertlm, koi.backendById("litert-lm"))
        assertNull(koi.backendFor("/m/model.onnx"))
        assertTrue(gguf.loaders.isEmpty(), "inspecting must not construct a loader")
    }

    @Test
    fun duplicateBackendIdsAreRejected() {
        // Two backends answering to one id makes resolution order decide which engine runs.
        val failure = assertFailsWith<IllegalArgumentException> {
            Koinference(gguf, FakeBackend("llama.cpp", listOf(".bin")))
        }
        assertTrue(failure.message!!.contains("llama.cpp"), failure.message!!)
    }

    @Test
    fun anEmptyRegistryIsRejected() {
        assertFailsWith<IllegalArgumentException> { Koinference(emptyList()) }
    }
}
