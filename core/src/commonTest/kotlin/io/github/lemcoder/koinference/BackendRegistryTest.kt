package io.github.lemcoder.koinference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeBackend(
    override val id: String,
    private val extensions: List<String>,
    override val honours: Set<SamplingKnob> = emptySet(),
) : Backend {
    override fun handles(modelPath: String) = extensions.any { modelPath.endsWith(it) }
    override fun loader(config: ModelConfig): ModelLoader = error("not needed")
}

class BackendRegistryTest {

    private val gguf = FakeBackend("llama.cpp", listOf(".gguf"))
    private val litertlm = FakeBackend("litert-lm", listOf(".litertlm", ".task"))
    private val registry = BackendRegistry(gguf, litertlm)

    @Test
    fun resolvesByIdAndByContainer() {
        assertEquals(gguf, registry.byId("llama.cpp"))
        assertEquals(litertlm, registry.forModel("/m/model.task"))
        assertEquals(gguf, registry.forModel("/m/model.gguf"))
    }

    @Test
    fun anUnregisteredContainerIsNullRatherThanAGuess() {
        // Picking "whichever backend is first" would load a GGUF into an engine that cannot read
        // it and fail somewhere far less legible.
        assertNull(registry.forModel("/m/model.tflite"))
        assertNull(registry.byId("mlx"))
    }

    @Test
    fun failuresNameWhatIsRegistered() {
        // The usual cause is a typo or a module that was not depended on, and neither is
        // diagnosable from "unknown backend".
        val byId = assertFailsWith<IllegalStateException> { registry.requireById("llama-cpp") }
        assertTrue(byId.message!!.contains("llama.cpp"), byId.message!!)

        val byModel = assertFailsWith<IllegalStateException> { registry.requireForModel("/m/x.onnx") }
        assertTrue(byModel.message!!.contains("litert-lm"), byModel.message!!)
    }

    @Test
    fun duplicateIdsAreRejectedWhenTheRegistryIsBuilt() {
        // Two backends answering to one id makes resolution order decide which engine runs, and
        // a results file would then name an engine that did not produce it.
        val failure = assertFailsWith<IllegalArgumentException> {
            BackendRegistry(gguf, FakeBackend("llama.cpp", listOf(".bin")))
        }
        assertTrue(failure.message!!.contains("llama.cpp"), failure.message!!)
    }

    @Test
    fun registrationOrderIsPreserved() {
        // `engine=all` runs them in this order, and a benchmark's first engine is the only one
        // that sees an untouched process.
        assertEquals(listOf("llama.cpp", "litert-lm"), registry.ids)
    }
}

class ModelConfigTest {

    @Test
    fun defaultsLeaveEveryEngineChoiceAlone() {
        // 0 and null mean "the engine's own", so an unset field never imposes a value that
        // happens to be this library's opinion.
        val config = ModelConfig()

        assertEquals(0, config.contextTokens)
        assertEquals(0, config.maxOutputTokens)
        assertEquals(0, config.threads)
        assertNull(config.systemPrompt)
        assertNull(config.cacheDir)
        assertEquals(Accelerator.CPU, config.settings.accelerator)
        assertEquals(GenerationParameters(), config.parameters)
    }
}
