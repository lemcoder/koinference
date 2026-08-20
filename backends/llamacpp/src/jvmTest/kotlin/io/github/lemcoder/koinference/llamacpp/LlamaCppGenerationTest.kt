package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.runtime.GenerationConstraint
import io.github.lemcoder.koinference.runtime.GenerationParameters
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The JVM leg end to end: generated JNI bridges, the facade behind them, llama.cpp behind that.
 *
 * Generation needs a real model, so those tests skip unless KOI_TEST_GGUF points at a .gguf.
 * llama.cpp's own stories260K.gguf (1.2 MB) is enough and is what CI uses:
 *
 *     KOI_TEST_GGUF=/path/to/stories260K.gguf ./gradlew :backends:llamacpp:jvmTest
 */
class LlamaCppGenerationTest {

    private val modelPath: String? = System.getenv("KOI_TEST_GGUF")

    @Test
    fun `loading a missing model fails`() = runTest {
        // Reaching a failure at all means the stub library resolved; an unresolved one would
        // surface as UnsatisfiedLinkError before the message below is ever built.
        val failure = assertFailsWith<IllegalStateException> {
            LlamaCppModelLoader().load("/nonexistent/model.gguf")
        }
        assertTrue(failure.message!!.contains("/nonexistent/model.gguf"))
    }

    @Test
    fun `load caches the runtime per model path`() = runTest {
        val path = modelPath ?: return@runTest

        val loader = LlamaCppModelLoader()
        try {
            assertSame(loader.load(path), loader.load(path))
        } finally {
            loader.unload(path)
        }
    }

    @Test
    fun `generates from a real model`() = runTest {
        val path = modelPath ?: return@runTest

        val loader = LlamaCppModelLoader(ModelConfig(maxOutputTokens = 16, contextTokens = 256))
        val runtime = loader.load(path)
        assertIs<LlamaCppTextRuntime>(runtime)
        try {
            val reply = runtime.generateResponse("Once upon a time")
            assertTrue(reply.isNotBlank(), "expected generated text, got: '$reply'")
        } finally {
            loader.unload(path)
        }
    }

    @Test
    fun `honours a json schema constraint`() = runTest {
        val path = modelPath ?: return@runTest

        val loader = LlamaCppModelLoader(ModelConfig(maxOutputTokens = 64, contextTokens = 256))
        val runtime = loader.load(path)
        assertIs<LlamaCppTextRuntime>(runtime)
        try {
            val schema = """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""
            val reply = runtime.generateResponse(
                prompt = "Name a capital city.",
                constraint = GenerationConstraint.JsonSchema(schema),
            )

            // The grammar constrains sampling token by token, so this holds for stories260K,
            // which has no idea what JSON is.
            assertTrue(reply.trimStart().startsWith("{"), "expected a JSON object, got: '$reply'")
            assertTrue(reply.contains("\"city\""), "expected the schema's field, got: '$reply'")
        } finally {
            loader.unload(path)
        }
    }

    @Test
    fun `rejects a schema that does not convert`() = runTest {
        val path = modelPath ?: return@runTest

        val loader = LlamaCppModelLoader()
        val runtime = loader.load(path)
        assertIs<LlamaCppTextRuntime>(runtime)
        try {
            assertFailsWith<IllegalArgumentException> {
                runtime.generateResponse("hello", GenerationConstraint.JsonSchema("{not json"))
            }
        } finally {
            loader.unload(path)
        }
    }

    @Test
    fun `changing generation parameters rebuilds the session`() = runTest {
        val path = modelPath ?: return@runTest

        val loader = LlamaCppModelLoader(ModelConfig(maxOutputTokens = 8, contextTokens = 256))
        val runtime = loader.load(path) as LlamaCppRuntime
        try {
            runtime.generateResponse("Once upon a time")
            runtime.updateGenerationParameters(GenerationParameters(topK = 1, minP = 0.0))

            // The session was freed by the update; generating again has to build a new one
            // rather than use the dangling handle.
            val reply = runtime.generateResponse("Once upon a time")
            assertTrue(reply.isNotBlank(), "expected generated text, got: '$reply'")
        } finally {
            loader.unload(path)
        }
    }
}
