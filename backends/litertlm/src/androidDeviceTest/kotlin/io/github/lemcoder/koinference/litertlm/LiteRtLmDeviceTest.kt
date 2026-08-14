package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.GenerationConstraint
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Runs on a device or emulator, which is the only place the AAR's `liblitertlm_jni.so` is
 * loaded by ART and LiteRT-LM executes on the target ABI. Everything else about the Android
 * leg is structural.
 *
 * Push the model first — there is no small one, so it is not committed and CI does not fetch it:
 *
 *     adb push SmolLM2_135M_Instruct.litertlm /data/local/tmp/koinference/
 *
 * [generatesFromARealModel] and [honoursAJsonSchemaConstraint] skip when it is absent, so a bare
 * `connectedAndroidDeviceTest` still reports on loading and on the library resolving at all.
 *
 * Test methods have block bodies on purpose: JUnit4 rejects a test whose method is not void, and
 * `fun x() = runBlocking { … }` returns the block's value.
 */
class LiteRtLmDeviceTest {

    private val modelPath = "/data/local/tmp/koinference/SmolLM2_135M_Instruct.litertlm"

    private fun modelPresent(): Boolean = File(modelPath).isFile

    @Test
    fun rejectsANonLiteRtLmPath() {
        runBlocking {
            assertFailsWith<IllegalArgumentException> {
                LiteRtLmModelLoader().load("/data/local/tmp/model.gguf")
            }
        }
    }

    @Test
    fun missingModelFails() {
        runBlocking {
            // Reaching a failure here means the native library resolved and ART called into
            // it; an unresolved library would surface as UnsatisfiedLinkError instead.
            val failure = runCatching {
                LiteRtLmModelLoader().load("/data/local/tmp/does-not-exist.litertlm")
            }.exceptionOrNull()

            assertTrue(failure != null, "expected loading a missing model to fail")
            assertTrue(
                failure !is UnsatisfiedLinkError,
                "liblitertlm_jni.so did not load: $failure",
            )
        }
    }

    @Test
    fun generatesFromARealModel() {
        if (!modelPresent()) return

        runBlocking {
            val loader = LiteRtLmModelLoader(systemPrompt = "You are terse.")
            val runtime = loader.load(modelPath)
            try {
                val reply = runtime.generateResponse("Say hello.")
                assertTrue(reply.isNotBlank(), "expected generated text, got: '$reply'")
            } finally {
                loader.unload(modelPath)
            }
        }
    }

    @Test
    fun honoursAJsonSchemaConstraint() {
        if (!modelPresent()) return

        runBlocking {
            val loader = LiteRtLmModelLoader()
            val runtime = loader.load(modelPath)
            try {
                val schema =
                    """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""
                val reply = runtime.generateResponse(
                    prompt = "Name a capital city.",
                    constraint = GenerationConstraint.JsonSchema(schema),
                )
                // Proves llguidance is present in the AAR's runtime, not only in the Apple
                // prebuilt — the two ship separately and could differ.
                assertTrue(
                    reply.trimStart().startsWith("{"),
                    "expected a JSON object, got: '$reply'",
                )
                assertTrue(reply.contains("\"city\""), "expected the schema's field, got: '$reply'")
            } finally {
                loader.unload(modelPath)
            }
        }
    }
}
