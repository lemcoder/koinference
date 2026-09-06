package io.github.lemcoder.koinference.cera

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.cera.internal.FakeCeraBridge
import io.github.lemcoder.koinference.prompt.promptOf
import io.github.lemcoder.koinference.runtime.KoinferenceException
import io.github.lemcoder.koinference.runtime.generation.GenerationConstraint
import io.github.lemcoder.koinference.runtime.generation.GenerationParameters
import io.github.lemcoder.koinference.runtime.media.ResponsePart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CeraConnectionTest {

    private val bridge = FakeCeraBridge()

    private suspend fun connection(config: ModelConfig = ModelConfig()): CeraGeneratingConnection =
        CeraModelLoader(bridge = bridge, config = config).load("/m/a.gguf").open() as CeraGeneratingConnection

    private suspend fun CeraGeneratingConnection.streamed(prompt: String): List<String> {
        val parts = mutableListOf<ResponsePart>()
        generate(promptOf(prompt)) { parts += it }
        return parts.textParts()
    }

    @Test
    fun `a reply is text parts`() = runTest {
        val reply = connection().generateAll("hello")
        assertTrue(reply.all { it is ResponsePart.Text })
        assertEquals("reply to hello", reply.text())
    }

    @Test
    fun `a stream arrives in pieces`() = runTest {
        val parts = connection().streamed("hello")
        assertTrue(parts.size > 1, "expected a stream, got ${parts.size} part")
        assertEquals("reply to hello", parts.joinToString(""))
    }

    @Test
    fun `one session serves every turn`() = runTest {
        val conn = connection()
        conn.generateAll("first"); conn.generateAll("second")
        assertEquals(1, bridge.model.sessions.size)
        assertEquals(listOf("first", "second"), bridge.model.session.prompts)
    }

    @Test
    fun `every turn starts from an empty context`() = runTest {
        val conn = connection()
        conn.generateAll("first"); conn.generateAll("second"); conn.streamed("third")
        assertEquals(1, bridge.model.sessions.size)
        assertEquals(2, bridge.model.session.resets)
    }

    @Test
    fun `changing the sampler opens a new session and keeps the weights`() = runTest {
        val conn = connection()
        conn.generateAll("first")
        val original = bridge.model.session
        conn.updateGenerationParameters(GenerationParameters(temperature = 0.9, seed = 3))
        conn.generateAll("second")
        assertEquals(1, bridge.models.size, "the weights must not be reloaded for a sampler change")
        assertEquals(2, bridge.model.sessions.size)
        assertTrue(original.closed)
        assertNotSame(original, bridge.model.session)
        assertEquals(0.9, bridge.model.session.options.temperature)
        assertEquals(3, bridge.model.session.options.seed)
        assertEquals(GenerationParameters(temperature = 0.9, seed = 3), conn.generationParameters)
    }

    @Test
    fun `token counts come from the model's own tokenizer`() = runTest {
        assertEquals(3, connection().countTokens("one two three"))
    }

    @Test
    fun `a json schema is refused rather than silently ignored`() = runTest {
        assertFailsWith<IllegalStateException> {
            connection().generateAll(promptOf("hi"), GenerationConstraint.JsonSchema("{}"))
        }
    }

    @Test
    fun `a closed connection refuses to be used`() = runTest {
        val conn = connection()
        conn.generateAll("first")
        val session = bridge.model.session
        conn.close()
        assertFailsWith<KoinferenceException.ConnectionClosed> { conn.generateAll("hi") }
        assertTrue(session.closed, "the session must be freed, not just dropped")
    }
}
