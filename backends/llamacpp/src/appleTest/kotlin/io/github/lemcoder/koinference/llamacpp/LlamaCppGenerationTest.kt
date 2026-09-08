package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.prompt.promptOf
import io.github.lemcoder.koinference.runtime.GeneratingConnection
import io.github.lemcoder.koinference.runtime.generation.GenerationConstraint
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class LlamaCppGenerationTest {

    private val modelPath: String? = getenv("KOI_TEST_GGUF")?.toKString()

    @Test
    fun loadingAMissingModelFails() = runTest {
        val failure = assertFailsWith<IllegalStateException> {
            LlamaCppModelLoader().load("/nonexistent/model.gguf").open()
        }
        assertTrue(failure.message!!.contains("/nonexistent/model.gguf"))
    }

    @Test
    fun generatesFromARealModel() = runTest {
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
    fun honoursAJsonSchemaConstraint() = runTest {
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
}
