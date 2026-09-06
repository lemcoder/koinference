package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.runtime.GeneratingConnection
import io.github.lemcoder.koinference.runtime.generation.GenerationConstraint
import io.github.lemcoder.koinference.runtime.generation.GenerationParameters
import io.github.lemcoder.koinference.prompt.promptOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LlamaCppGenerationTest {

    private val modelPath: String? = System.getenv("KOI_TEST_GGUF")

    @Test
    fun `loading a missing model fails`() = runTest {
        val failure = assertFailsWith<IllegalStateException> {
            LlamaCppModelLoader().load("/nonexistent/model.gguf").open()
        }
        assertTrue(failure.message!!.contains("/nonexistent/model.gguf"))
    }

    @Test
    fun `load caches the model per path`() = runTest {
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
        val conn = loader.load(path).open()
        assertIs<GeneratingConnection>(conn)
        try {
            val reply = conn.generateAll("Once upon a time").text()
            assertTrue(reply.isNotBlank(), "expected generated text, got: '$reply'")
        } finally {
            conn.close(); loader.unload(path)
        }
    }

    @Test
    fun `honours a json schema constraint`() = runTest {
        val path = modelPath ?: return@runTest
        val loader = LlamaCppModelLoader(ModelConfig(maxOutputTokens = 64, contextTokens = 256))
        val conn = loader.load(path).open() as GeneratingConnection
        try {
            val schema = """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""
            val reply = conn.generateAll(promptOf("Name a capital city."),
                GenerationConstraint.JsonSchema(schema)).text()
            assertTrue(reply.trimStart().startsWith("{"), "expected a JSON object, got: '$reply'")
            assertTrue(reply.contains("\"city\""), "expected the schema's field, got: '$reply'")
        } finally {
            conn.close(); loader.unload(path)
        }
    }

    @Test
    fun `rejects a schema that does not convert`() = runTest {
        val path = modelPath ?: return@runTest
        val loader = LlamaCppModelLoader()
        val conn = loader.load(path).open() as GeneratingConnection
        try {
            assertFailsWith<IllegalArgumentException> {
                conn.generateAll(promptOf("hello"), GenerationConstraint.JsonSchema("{not json")).text()
            }
        } finally {
            conn.close(); loader.unload(path)
        }
    }

    @Test
    fun `changing generation parameters rebuilds the session`() = runTest {
        val path = modelPath ?: return@runTest
        val loader = LlamaCppModelLoader(ModelConfig(maxOutputTokens = 8, contextTokens = 256))
        val conn = loader.load(path).open() as GeneratingConnection
        try {
            conn.generateAll("Once upon a time").text()
            conn.updateGenerationParameters(GenerationParameters(topK = 1, minP = 0.0))
            val reply = conn.generateAll("Once upon a time").text()
            assertTrue(reply.isNotBlank(), "expected generated text, got: '$reply'")
        } finally {
            conn.close(); loader.unload(path)
        }
    }
}
