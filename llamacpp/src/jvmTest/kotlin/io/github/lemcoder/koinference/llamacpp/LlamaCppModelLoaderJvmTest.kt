package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.GenerationConstraint
import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.InferenceBackend
import io.github.lemcoder.koinference.RuntimeSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class LlamaCppModelLoaderJvmTest {

    @Test
    fun `load returns runtime for gguf models`() = runTest {
        val loader = LlamaCppModelLoader()
        val runtime = loader.load("test-model.gguf")

        assertNotNull(runtime)
    }

    @Test
    fun `load returns cached runtime for same model path`() = runTest {
        val loader = LlamaCppModelLoader()
        val runtimeA = loader.load("test-model.gguf")
        val runtimeB = loader.load("test-model.gguf")

        assertSame(runtimeA, runtimeB)
    }

    @Test
    fun `load rejects non gguf models`() = runTest {
        val loader = LlamaCppModelLoader()

        assertFailsWith<IllegalArgumentException> {
            loader.load("test-model.bin")
        }
    }

    @Test
    fun `runtime settings and generation parameters can be updated`() = runTest {
        val loader = LlamaCppModelLoader()
        val runtime = loader.load("test-model.gguf") as LlamaCppRuntime
        val parameters = GenerationParameters(topK = 40, minP = 0.1)
        val settings = RuntimeSettings(backend = InferenceBackend.GPU)

        runtime.updateGenerationParameters(parameters)
        runtime.updateRuntimeSettings(settings)

        assertEquals(parameters, runtime.generationParameters)
        assertEquals(settings, runtime.runtimeSettings)
    }

    @Test
    fun `runtime can generate response with schema constraints`() = runTest {
        val loader = LlamaCppModelLoader()
        val runtime = loader.load("test-model.gguf")

        val response = runtime.generateResponse(
            prompt = "hello",
            constraint = GenerationConstraint.JsonSchema("""{"type":"object"}"""),
        )

        assertEquals(
            "Stub llama.cpp response for \"hello\" from test-model.gguf with schema constraints",
            response,
        )
    }
}
