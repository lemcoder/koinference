package io.github.lemcoder.koinference.executorch

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.executorch.internal.FakeExecuTorchBridge
import io.github.lemcoder.koinference.executorch.internal.FakeSystemFiles
import io.github.lemcoder.koinference.prompt.promptOf
import io.github.lemcoder.koinference.runtime.KoinferenceException
import io.github.lemcoder.koinference.runtime.generation.GenerationConstraint
import io.github.lemcoder.koinference.runtime.generation.GenerationParameters
import io.github.lemcoder.koinference.runtime.media.ResponsePart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ExecuTorchConnectionTest {

    private val bridge = FakeExecuTorchBridge()
    private val files = FakeSystemFiles(setOf("/m/tokenizer.model"))

    private fun loader(config: ModelConfig = ModelConfig()) =
        ExecuTorchModelLoader(bridge = bridge, config = config, files = files)

    private suspend fun connection(config: ModelConfig = ModelConfig()): ExecuTorchGeneratingConnection =
        loader(config).load("/m/a.pte").open() as ExecuTorchGeneratingConnection

    @Test
    fun `a reply is text parts`() = runTest {
        val reply = connection().generateAll("hello")
        assertTrue(reply.all { it is ResponsePart.Text })
        assertEquals("reply to hello", reply.text())
    }

    @Test
    fun `a stream arrives in pieces`() = runTest {
        val parts = mutableListOf<String>()
        connection().generate("hello") { if (it is ResponsePart.Text) parts += it.text }
        assertTrue(parts.size > 1, "expected a stream, got ${parts.size} part")
        assertEquals("reply to hello", parts.joinToString(""))
    }

    @Test
    fun `the tokenizer beside the model is what gets loaded`() = runTest {
        connection()
        assertEquals("/m/tokenizer.model", bridge.model.options.tokenizerPath)
    }

    @Test
    fun `a model with no tokenizer fails naming what it looked for`() = runTest {
        val bare = ExecuTorchModelLoader(bridge, ModelConfig(), FakeSystemFiles(emptySet()))
        val failure = assertFailsWith<IllegalStateException> { bare.load("/m/a.pte") }
        assertTrue(failure.message!!.contains("tokenizer.model"), failure.message!!)
        assertTrue(bridge.models.isEmpty(), "nothing should be loaded without a tokenizer")
    }

    @Test
    fun `temperature is fixed at load, so changing it is refused`() = runTest {
        val conn = connection(ModelConfig(parameters = GenerationParameters(temperature = 0.2)))
        assertEquals(0.2, bridge.model.options.temperature)
        assertFailsWith<KoinferenceException.Unsupported> {
            conn.updateGenerationParameters(GenerationParameters(temperature = 0.9))
        }
        assertEquals(1, bridge.models.size, "temperature is load-fixed; it must not silently reload")
    }

    @Test
    fun `a constraint is refused rather than silently dropped`() = runTest {
        assertFailsWith<IllegalStateException> {
            connection().generateAll(promptOf("hi"), GenerationConstraint.JsonSchema("{}"))
        }
    }

    @Test
    fun `counts the tokens the engine reported for its own reply`() = runTest {
        val conn = connection()
        val reply = conn.generateAll("hello").text()
        assertEquals(3, conn.countTokens(reply))
    }

    @Test
    fun `refuses to count any other text`() = runTest {
        val conn = connection()
        conn.generateAll("hello")
        assertTrue(conn.countTokens("some other text") < 0)
    }

    @Test
    fun `a closed connection refuses to be used`() = runTest {
        val conn = connection()
        conn.generateAll("first")
        conn.close()
        assertFailsWith<KoinferenceException.ConnectionClosed> { conn.generateAll("hi") }
    }
}
