package io.github.lemcoder.koinference.cera

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.cera.internal.FakeCeraBridge
import io.github.lemcoder.koinference.runtime.Accelerator
import io.github.lemcoder.koinference.runtime.GenerationConstraint
import io.github.lemcoder.koinference.runtime.GenerationParameters
import io.github.lemcoder.koinference.runtime.ResponsePart
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * What the runtime decides, checked without a GGUF.
 *
 * This is the payoff of the interface seam: session reuse, what a parameter change throws away,
 * and use-after-unload are all common-code decisions, and none of them needs a real engine.
 */
class CeraRuntimeTest {

    private val bridge = FakeCeraBridge()

    private suspend fun runtime(config: ModelConfig = ModelConfig()) =
        CeraModelLoader(bridge = bridge, config = config).load("/m/a.gguf")

    @Test
    fun `a reply is text parts`() = runTest {
        val reply = runtime().generateResponse("hello")

        assertTrue(reply.all { it is ResponsePart.Text })
        assertEquals("reply to hello", reply.text())
    }

    @Test
    fun `a stream arrives in pieces`() = runTest {
        val parts = runtime().streamResponse("hello").toList().textParts()

        // More than one, or the binding buffered and time to first token is really total latency.
        assertTrue(parts.size > 1, "expected a stream, got ${parts.size} part")
        assertEquals("reply to hello", parts.joinToString(""))
    }

    @Test
    fun `one session serves every turn`() = runTest {
        val runtime = runtime()

        runtime.generateResponse("first")
        runtime.generateResponse("second")

        // A session holds the KV cache: one per call would re-prefill every turn.
        assertEquals(1, bridge.model.sessions.size)
        assertEquals(listOf("first", "second"), bridge.model.session.prompts)
    }

    @Test
    fun `changing the sampler opens a new session and keeps the weights`() = runTest {
        val runtime = runtime()
        runtime.generateResponse("first")
        val original = bridge.model.session

        runtime.updateGenerationParameters(GenerationParameters(temperature = 0.9, seed = 3))
        runtime.generateResponse("second")

        // Cera fixes the sampler when a session is opened, so the session goes; the model stays.
        assertEquals(1, bridge.models.size, "the weights must not be reloaded for a sampler change")
        assertEquals(2, bridge.model.sessions.size)
        assertTrue(original.closed)
        assertNotSame(original, bridge.model.session)
        assertEquals(0.9, bridge.model.session.options.temperature)
        assertEquals(3, bridge.model.session.options.seed)
        assertEquals(GenerationParameters(temperature = 0.9, seed = 3), runtime.generationParameters)
    }

    @Test
    fun `changing the device reloads the weights`() = runTest {
        val runtime = runtime(ModelConfig(settings = RuntimeSettings(accelerator = Accelerator.CPU)))
        runtime.generateResponse("first")

        runtime.updateRuntimeSettings(RuntimeSettings(accelerator = Accelerator.GPU))

        // Cera picks its backend when the engine is created, so this cannot be a session rebuild.
        assertEquals(2, bridge.models.size)
        assertEquals(Accelerator.GPU, bridge.model.options.accelerator)
        assertEquals(Accelerator.GPU, runtime.runtimeSettings.accelerator)
    }

    @Test
    fun `the same device is not a reload`() = runTest {
        val runtime = runtime(ModelConfig(settings = RuntimeSettings(accelerator = Accelerator.CPU)))

        runtime.updateRuntimeSettings(RuntimeSettings(accelerator = Accelerator.CPU))

        assertEquals(1, bridge.models.size, "reloading a 1.2B model for no change is not free")
    }

    @Test
    fun `token counts come from the model's own tokenizer`() = runTest {
        assertEquals(3, runtime().countTokens("one two three"))
    }

    @Test
    fun `a json schema is refused rather than silently ignored`() = runTest {
        // Cera constrains with GBNF and its bindings expose no schema converter. Failing here beats
        // generating unconstrained text that looks like it honoured the schema.
        assertFailsWith<IllegalStateException> {
            runtime().generateResponse("hi", GenerationConstraint.JsonSchema("{}"))
        }
    }

    @Test
    fun `an unloaded runtime refuses to be used`() = runTest {
        val loader = CeraModelLoader(bridge = bridge, config = ModelConfig())
        val runtime = loader.load("/m/a.gguf")
        runtime.generateResponse("first")
        val session = bridge.model.session

        loader.unload("/m/a.gguf")

        // An exception, not a use-after-free: the handles behind this are gone.
        assertFailsWith<IllegalStateException> { runtime.generateResponse("hi") }
        assertTrue(session.closed, "the session must be freed, not just dropped")
        assertTrue(bridge.model.closed)
    }
}
