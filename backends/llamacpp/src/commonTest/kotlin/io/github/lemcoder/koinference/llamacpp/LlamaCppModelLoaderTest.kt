package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.RuntimeSettings
import io.github.lemcoder.koinference.llamacpp.internal.FakeLlamaCppBridge
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LlamaCppModelLoaderTest {

    private val bridge = FakeLlamaCppBridge()

    private fun loader() = LlamaCppModelLoader(
        bridge = bridge,
        systemPrompt = null,
        settings = RuntimeSettings(),
        parameters = GenerationParameters(),
        nCtx = 0,
        nThreads = 0,
        nPredict = 0,
    )

    @Test
    fun `load rejects non gguf models`() = runTest {
        val failure = assertFailsWith<IllegalArgumentException> {
            loader().load("test-model.bin")
        }
        assertTrue(failure.message!!.contains(".gguf"))
        // Rejected before anything was loaded.
        assertTrue(bridge.models.isEmpty())
    }

    @Test
    fun `the same path loads the weights once`() = runTest {
        val loader = loader()

        val first = loader.load("/models/a.gguf")
        val second = loader.load("/models/a.gguf")

        assertSame(first, second)
        assertEquals(1, bridge.models.size)
    }

    @Test
    fun `different paths get their own runtimes`() = runTest {
        val loader = loader()

        loader.load("/models/a.gguf")
        loader.load("/models/b.gguf")

        assertEquals(
            listOf("/models/a.gguf", "/models/b.gguf"),
            bridge.models.map { it.options.modelPath },
        )
    }

    @Test
    fun `unload frees the weights and a later load is a fresh one`() = runTest {
        val loader = loader()
        val first = loader.load("/models/a.gguf")

        loader.unload("/models/a.gguf")
        assertTrue(bridge.models.single().closed)

        val second = loader.load("/models/a.gguf")
        assertEquals(2, bridge.models.size)
        assertTrue(first !== second)
    }

    @Test
    fun `unloading an unknown path is not an error`() = runTest {
        loader().unload("/models/never-loaded.gguf")
    }

    @Test
    fun `unloadAll frees everything and leaves the loader usable`() = runTest {
        val loader = loader()
        loader.load("/models/a.gguf")
        loader.load("/models/b.gguf")

        loader.unloadAll()
        assertTrue(bridge.models.all { it.closed })

        loader.load("/models/a.gguf")
        assertEquals(3, bridge.models.size)
    }
}
