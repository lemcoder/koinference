package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.GenerationConstraint
import android.util.Log
import io.github.lemcoder.koinference.InstrumentedRuntime
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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

    /**
     * Records which telemetry source this device actually produced.
     *
     * The SDK computes TTFT and token counts itself, but nothing in the public EngineConfig
     * turns benchmarking on, so whether getBenchmarkInfo() holds real numbers or zeros can only
     * be settled on a device. The benchmark harness handles both — engine numbers when they are
     * there, the streamed first chunk when they are not — and this test writes the answer into
     * the log so the first run on new hardware states it rather than leaving it assumed.
     *
     * It asserts only what must hold either way: some first-token measurement exists.
     */
    @Test
    fun reportsWhichTelemetrySourceThisDeviceProduces() {
        if (!modelPresent()) return

        runBlocking {
            val loader = LiteRtLmModelLoader(maxOutputTokens = 32)
            val runtime = loader.load(modelPath)
            try {
                runtime.generateResponse("Say hello.")
                val telemetry = (runtime as InstrumentedRuntime).lastGeneration
                assertNotNull(telemetry, "the Android binding always reports something")

                Log.i(
                    "koinference-benchmark",
                    "LiteRT-LM telemetry: source=${telemetry.source} " +
                        "ttftMs=${telemetry.timeToFirstTokenMs} " +
                        "promptTokens=${telemetry.promptTokens} " +
                        "decodeTokens=${telemetry.decodeTokens} " +
                        "decodeTps=${telemetry.decodeTokensPerSecond} " +
                        "engineInitMs=${telemetry.engineInitMs}",
                )

                // Either source is acceptable; a missing first-token time is not.
                assertNotNull(
                    telemetry.timeToFirstTokenMs,
                    "no time to first token from either source",
                )
                assertTrue(telemetry.timeToFirstTokenMs!! > 0.0)
            } finally {
                loader.unload(modelPath)
            }
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
