package io.github.lemcoder.koinference.cera

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.cera.internal.FakeCeraBridge
import io.github.lemcoder.koinference.runtime.Accelerator
import io.github.lemcoder.koinference.runtime.GenerationParameters
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CeraModelLoaderTest {

    private val bridge = FakeCeraBridge()

    private fun loader(config: ModelConfig = ModelConfig()) =
        CeraModelLoader(bridge = bridge, config = config)

    @Test
    fun `load rejects a container this engine does not read`() = runTest {
        val failure = assertFailsWith<IllegalArgumentException> { loader().load("/m/model.litertlm") }

        assertTrue(failure.message!!.contains(".gguf"))
        assertTrue(bridge.models.isEmpty(), "rejected before anything was loaded")
    }

    @Test
    fun `the same path loads the weights once`() = runTest {
        val loader = loader()

        val first = loader.load("/m/a.gguf")
        val second = loader.load("/m/a.gguf")

        assertSame(first, second)
        assertEquals(1, bridge.models.size)
    }

    @Test
    fun `unload frees the model rather than just dropping it`() = runTest {
        val loader = loader()
        loader.load("/m/a.gguf")

        loader.unload("/m/a.gguf")

        assertTrue(bridge.model.closed, "the engine is Rust-side memory and must be freed")
        // A second load is a fresh model, not the freed one handed back.
        loader.load("/m/a.gguf")
        assertEquals(2, bridge.models.size)
    }

    @Test
    fun `the config's vocabulary is mapped onto the engine's here`() = runTest {
        loader(
            ModelConfig(
                settings = RuntimeSettings(accelerator = Accelerator.CPU),
                parameters = GenerationParameters(temperature = 0.4, topK = 7, seed = 11),
                contextTokens = 2048,
                maxOutputTokens = 64,
            ),
        ).load("/m/a.gguf").generateResponse("hi")

        assertEquals(2048, bridge.model.options.contextTokens)
        assertEquals(Accelerator.CPU, bridge.model.options.accelerator)

        val session = bridge.model.session.options
        assertEquals(64, session.maxOutputTokens)
        assertEquals(0.4, session.temperature)
        assertEquals(7, session.topK)
        assertEquals(11, session.seed)
    }
}
