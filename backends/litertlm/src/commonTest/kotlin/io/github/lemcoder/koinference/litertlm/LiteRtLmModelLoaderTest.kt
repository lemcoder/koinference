package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.RuntimeSettings
import io.github.lemcoder.koinference.litertlm.internal.FakeLiteRtLmBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LiteRtLmModelLoaderTest {

    private val bridge = FakeLiteRtLmBridge()

    private fun loader(cacheDir: String? = null) = LiteRtLmModelLoader(
        bridge = bridge,
        cacheDir = cacheDir,
        systemPrompt = null,
        settings = RuntimeSettings(),
        parameters = GenerationParameters(),
        nThreads = 4,
        maxTokens = 512,
    )

    @Test
    fun rejectsAGgufPath() = runTest {
        val failure = assertFailsWith<IllegalArgumentException> {
            LiteRtLmModelLoader().load("/models/tinyllama.gguf")
        }
        assertTrue(failure.message!!.contains(".litertlm"))
    }

    @Test
    fun rejectsARawTfliteModel() = runTest {
        // LiteRT-LM refuses these itself; failing here keeps the error legible instead of
        // surfacing as a null engine handle from the facade.
        assertFailsWith<IllegalArgumentException> {
            LiteRtLmModelLoader().load("/models/model.tflite")
        }
    }

    @Test
    fun passesItsConfigurationToTheEngine() = runTest {
        loader(cacheDir = "/tmp/koi").load("/models/smol.litertlm")

        val options = bridge.engines.single().options
        assertEquals("/models/smol.litertlm", options.modelPath)
        assertEquals("/tmp/koi", options.cacheDir)
        assertEquals(4, options.nThreads)
        assertEquals(512, options.maxTokens)
    }

    @Test
    fun loadsAModelOnce() = runTest {
        val loader = loader()

        val first = loader.load("/models/smol.litertlm")
        val second = loader.load("/models/smol.litertlm")

        assertSame(first, second)
        assertEquals(1, bridge.engines.size)
    }

    @Test
    fun concurrentLoadsOfOnePathLoadOnce() = runTest {
        val loader = loader()

        // Weights are hundreds of megabytes: the second loader through here must find the
        // first one's engine, not start its own and then be dropped from the map with no way
        // left to free it.
        val runtimes = (1..8)
            .map { async(Dispatchers.Default) { loader.load("/models/smol.litertlm") } }
            .awaitAll()

        assertEquals(1, bridge.engines.size)
        assertEquals(1, runtimes.distinct().size)
    }

    @Test
    fun unloadReleasesTheEngine() = runTest {
        val loader = loader()
        loader.load("/models/smol.litertlm")

        loader.unload("/models/smol.litertlm")

        assertTrue(bridge.engines.single().closed)
        // And the path is loadable again afterwards.
        loader.load("/models/smol.litertlm")
        assertEquals(2, bridge.engines.size)
    }

    @Test
    fun unloadingAPathThatWasNeverLoadedDoesNothing() = runTest {
        loader().unload("/models/never.litertlm")

        assertTrue(bridge.engines.isEmpty())
    }

    @Test
    fun unloadAllReleasesEveryEngine() = runTest {
        val loader = loader()
        loader.load("/models/a.litertlm")
        loader.load("/models/b.task")

        loader.unloadAll()

        assertEquals(2, bridge.engines.size)
        assertTrue(bridge.engines.all { it.closed })
        // Idempotent, and the loader stays usable.
        loader.unloadAll()
        loader.load("/models/a.litertlm")
        assertEquals(3, bridge.engines.size)
    }
}
