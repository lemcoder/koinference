package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.GenerationConstraint
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Kotlin/Native leg of the same chain the JVM test covers: cinterop over the facade, the
 * facade over llama.cpp. Linking is proved by the missing-model test alone, which is why it is
 * not gated; generation needs KOI_TEST_GGUF to point at a .gguf.
 *
 * appleTest rather than nativeTest: linking a test executable against the archive pulls in
 * llama.cpp's platform dependencies, and the ones this project can name are Apple's
 * (`-framework Metal`, Accelerate — see the linkerOpts in build.gradle.kts). linuxX64 still
 * compiles and links the main klib, but nothing there calls into the facade, so its test binary
 * never needs them.
 */
@OptIn(ExperimentalForeignApi::class)
class LlamaCppGenerationTest {

    private val modelPath: String? = getenv("KOI_TEST_GGUF")?.toKString()

    @Test
    fun loadingAMissingModelFails() = runTest {
        val failure = assertFailsWith<IllegalStateException> {
            LlamaCppModelLoader().load("/nonexistent/model.gguf")
        }
        assertTrue(failure.message!!.contains("/nonexistent/model.gguf"))
    }

    @Test
    fun generatesFromARealModel() = runTest {
        val path = modelPath ?: return@runTest

        val loader = LlamaCppModelLoader(nPredict = 16, nCtx = 256)
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
    fun honoursAJsonSchemaConstraint() = runTest {
        val path = modelPath ?: return@runTest

        val loader = LlamaCppModelLoader(nPredict = 64, nCtx = 256)
        val runtime = loader.load(path)
        assertIs<LlamaCppTextRuntime>(runtime)
        try {
            val schema = """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""
            val reply = runtime.generateResponse(
                prompt = "Name a capital city.",
                constraint = GenerationConstraint.JsonSchema(schema),
            )
            assertTrue(reply.trimStart().startsWith("{"), "expected a JSON object, got: '$reply'")
            assertTrue(reply.contains("\"city\""), "expected the schema's field, got: '$reply'")
        } finally {
            loader.unload(path)
        }
    }
}
