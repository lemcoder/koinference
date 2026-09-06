package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.runtime.GeneratingConnection
import io.github.lemcoder.koinference.runtime.KoinferenceException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KoinferenceTest {

    private val gguf = FakeBackend("llama.cpp", listOf(".gguf"))
    private val litertlm = FakeBackend("litert-lm", listOf(".litertlm", ".task"))
    private val koi = Koinference(gguf, litertlm)

    private suspend fun Koinference.reply(path: String): String {
        val conn = openConnection(loadModel(path)) as GeneratingConnection
        return conn.generateAll("hi").text().also { conn.close() }
    }

    @Test
    fun loadsThroughWhicheverBackendReadsTheContainer() = runTest {
        assertEquals("reply from /m/a.gguf", koi.reply("/m/a.gguf"))
        assertEquals("reply from /m/b.task", koi.reply("/m/b.task"))

        assertEquals(1, gguf.loaders.size)
        assertEquals(1, litertlm.loaders.size)
    }

    @Test
    fun theSamePathIsLoadedOnce() = runTest {
        // Weights are the expensive part; two loads must return the same handle.
        assertEquals(koi.loadModel("/m/a.gguf"), koi.loadModel("/m/a.gguf"))
    }

    @Test
    fun anUnreadableContainerNamesWhatIsRegistered() = runTest {
        val failure = assertFailsWith<KoinferenceException.LoadFailed> { koi.loadModel("/m/model.onnx") }
        assertTrue(failure.message!!.contains("llama.cpp"), failure.message!!)
        assertTrue(failure.message!!.contains("litert-lm"), failure.message!!)
    }

    @Test
    fun unloadReachesTheLoaderThatLoaded() = runTest {
        val id = koi.loadModel("/m/a.gguf")
        koi.unloadModel(id)
        assertEquals(listOf("/m/a.gguf"), gguf.loaders.single().unloaded)
    }

    @Test
    fun unloadAllReachesEveryBackend() = runTest {
        koi.loadModel("/m/a.gguf")
        koi.loadModel("/m/b.litertlm")
        koi.unloadAll()
        assertEquals(listOf("/m/a.gguf"), gguf.loaders.single().unloaded)
        assertEquals(listOf("/m/b.litertlm"), litertlm.loaders.single().unloaded)
    }

    @Test
    fun theInstanceStaysUsableAfterUnloadAll() = runTest {
        koi.loadModel("/m/a.gguf")
        koi.unloadAll()
        assertEquals("reply from /m/a.gguf", koi.reply("/m/a.gguf"))
    }

    @Test
    fun unloadRefusesWhileAConnectionIsOpen() = runTest {
        val id = koi.loadModel("/m/a.gguf")
        koi.openConnection(id)
        assertFailsWith<KoinferenceException.LoadFailed> { koi.unloadModel(id) }
    }

    @Test
    fun forcedUnloadFiresOnDeathAndClosesTheConnection() = runTest {
        val id = koi.loadModel("/m/a.gguf")
        var death: KoinferenceException? = null
        val conn = koi.openConnection(id) { death = it } as GeneratingConnection
        koi.unloadModel(id, force = true)
        assertTrue(death is KoinferenceException.ModelUnloaded)
        assertFailsWith<KoinferenceException.ConnectionClosed> { conn.generate("hi") {} }
    }

    @Test
    fun openingOverAnUnknownModelThrows() = runTest {
        assertFailsWith<KoinferenceException.UnknownModel> { koi.openConnection(ModelId("nope")) }
    }

    @Test
    fun theConfigReachesEveryModelThisInstanceLoads() = runTest {
        val configured = Koinference(listOf(gguf), ModelConfig(contextTokens = 512))
        configured.loadModel("/m/a.gguf")
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
