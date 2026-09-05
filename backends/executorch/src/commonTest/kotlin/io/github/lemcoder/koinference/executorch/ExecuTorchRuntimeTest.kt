package io.github.lemcoder.koinference.executorch

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.executorch.internal.FakeExecuTorchBridge
import io.github.lemcoder.koinference.executorch.internal.FakeSystemFiles
import io.github.lemcoder.koinference.runtime.Accelerator
import io.github.lemcoder.koinference.runtime.GenerationConstraint
import io.github.lemcoder.koinference.runtime.GenerationParameters
import io.github.lemcoder.koinference.runtime.ResponsePart
import io.github.lemcoder.koinference.runtime.RuntimeSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class ExecuTorchRuntimeTest {

    private val bridge = FakeExecuTorchBridge()
    private val files = FakeSystemFiles(setOf("/m/tokenizer.model"))

    private fun loader(config: ModelConfig = ModelConfig()) =
        ExecuTorchModelLoader(bridge = bridge, config = config, files = files)

    private suspend fun runtime(config: ModelConfig = ModelConfig()) = loader(config).load("/m/a.pte")

    @Test
    fun `a reply is text parts`() = runTest {
        val reply = runtime().generateResponse("hello")

        assertTrue(reply.all { it is ResponsePart.Text })
        assertEquals("reply to hello", reply.text())
    }

    @Test
    fun `a stream arrives in pieces`() = runTest {
        val parts = runtime().streamResponse("hello").toList()
            .filterIsInstance<ResponsePart.Text>().map { it.text }

        assertTrue(parts.size > 1, "expected a stream, got ${parts.size} part")
        assertEquals("reply to hello", parts.joinToString(""))
    }

    @Test
    fun `the tokenizer beside the model is what gets loaded`() = runTest {
        runtime()

        assertEquals("/m/tokenizer.model", bridge.model.options.tokenizerPath)
    }

    @Test
    fun `a model with no tokenizer fails naming what it looked for`() = runTest {
        val bare = ExecuTorchModelLoader(bridge, ModelConfig(), FakeSystemFiles(emptySet()))

        val failure = assertFailsWith<IllegalStateException> { bare.load("/m/a.pte") }

        // The message is the point: without it this is a native crash inside LlmModule.
        assertTrue(failure.message!!.contains("tokenizer.model"), failure.message!!)
        assertTrue(bridge.models.isEmpty(), "nothing should be loaded without a tokenizer")
    }

    @Test
    fun `temperature is fixed at load, so changing it reloads the program`() = runTest {
        val runtime = runtime(ModelConfig(parameters = GenerationParameters(temperature = 0.2)))
        assertEquals(0.2, bridge.model.options.temperature)

        runtime.updateGenerationParameters(GenerationParameters(temperature = 0.9))

        // LlmModule takes temperature in its constructor, so this cannot be a session rebuild.
        assertEquals(2, bridge.models.size)
        assertEquals(0.9, bridge.model.options.temperature)
    }

    @Test
    fun `a device other than the CPU is refused rather than ignored`() = runTest {
        val runtime = runtime()

        // Where a .pte runs is decided when it is exported, so accepting GPU here would be a lie.
        assertFailsWith<IllegalStateException> {
            runtime.updateRuntimeSettings(RuntimeSettings(accelerator = Accelerator.GPU))
        }
        assertEquals(Accelerator.CPU, runtime.runtimeSettings.accelerator)
    }

    @Test
    fun `a constraint is refused rather than silently dropped`() = runTest {
        assertFailsWith<IllegalStateException> {
            runtime().generateResponse("hi", GenerationConstraint.JsonSchema("{}"))
        }
    }

    @Test
    fun `counts the tokens the engine reported for its own reply`() = runTest {
        val runtime = runtime()
        val reply = runtime.generateResponse("hello").text()

        // The engine's own tokenizer counted this; the harness divides by it.
        assertEquals(3, runtime.countTokens(reply))
    }

    @Test
    fun `refuses to count any other text`() = runTest {
        val runtime = runtime()
        runtime.generateResponse("hello")

        // Negative is the harness's "this engine did not say", which is the truth here: ExecuTorch
        // exposes no tokenizer to count a prompt with.
        assertTrue(runtime.countTokens("some other text") < 0)
    }

    @Test
    fun `an unloaded runtime refuses to be used`() = runTest {
        val loader = loader()
        val runtime = loader.load("/m/a.pte")
        runtime.generateResponse("first")

        loader.unload("/m/a.pte")

        assertFailsWith<IllegalStateException> { runtime.generateResponse("hi") }
        assertTrue(bridge.model.closed)
    }
}
