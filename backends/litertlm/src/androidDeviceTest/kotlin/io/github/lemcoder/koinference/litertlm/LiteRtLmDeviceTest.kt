package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.GenerationConstraint
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lemcoder.koinference.GenerationParameters
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * Which model to exercise, and whether its absence may pass silently.
     *
     * Passed explicitly, a missing file fails: a skip that looks like a pass is how a device
     * test reports four green runs without ever loading a model.
     *
     * Push it somewhere the app can *write*. /data/local/tmp belongs to shell, and while the
     * runtime can read a model there it cannot write XNNPACK's weight cache beside it — the
     * delegate then rebuilds every prefill signature on every load, which is seconds of setup
     * and gigabytes of RSS.
     *
     *     adb push model.litertlm /sdcard/Android/data/io.github.lemcoder.koinference.litertlm.test/files/
     *     ./gradlew :backends:litertlm:connectedAndroidDeviceTest \
     *         -Pandroid.testInstrumentationRunnerArguments.litertlmModel=/sdcard/Android/data/io.github.lemcoder.koinference.litertlm.test/files/model.litertlm
     */
    private val requestedModel: String? =
        InstrumentationRegistry.getArguments().getString("litertlmModel")

    private val modelPath: String =
        requestedModel ?: "/data/local/tmp/koinference/SmolLM2_135M_Instruct.litertlm"

    /**
     * A directory the runtime may write to.
     *
     * Without one, LiteRT-LM tries to put the delegate's weight cache next to the model and is
     * denied by SELinux when that is shell's directory. The app's own cache dir always works.
     */
    private val cacheDir: String =
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.absolutePath

    private fun modelPresent(): Boolean {
        if (File(modelPath).isFile) return true
        if (requestedModel != null) {
            throw AssertionError(
                "litertlmModel was set to $modelPath but no readable file is there. Push the " +
                    "model, or drop the argument to skip these tests.",
            )
        }
        return false
    }

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
     * Checks that the streamed chunks concatenate to the blocking reply.
     *
     * The Android binding is Google's SDK, and whether its flow emits deltas or the reply so
     * far is its business, not something documented. The Apple leg was observed to emit deltas
     * inside a JSON envelope; if this leg were cumulative instead, concatenation would produce
     * the reply repeated at every prefix length, and every benchmark record on Android would
     * carry a plausible-looking output length that is nonsense. Cheap to check on device,
     * impossible to check anywhere else.
     */
    @Test
    fun streamedChunksConcatenateToTheBlockingReply() {
        if (!modelPresent()) return

        runBlocking {
            val loader = LiteRtLmModelLoader(
                cacheDir = cacheDir,
                maxOutputTokens = 24,
                // Greedy, so the two calls are answering identically rather than by luck.
                parameters = GenerationParameters(temperature = 0.0, seed = 42),
            )
            val runtime = loader.load(modelPath)
            try {
                val streamed = runtime.streamResponse("Say hello.").toList()
                runtime.resetConversation()
                val blocking = runtime.generateResponse("Say hello.")

                Log.i(
                    "koinference-benchmark",
                    "LiteRT-LM streaming: chunks=${streamed.size} " +
                        "streamedChars=${streamed.sumOf { it.length }} blockingChars=${blocking.length}",
                )

                assertTrue(streamed.isNotEmpty(), "no chunks were emitted")
                assertEquals(blocking, streamed.joinToString(""))
            } finally {
                loader.unload(modelPath)
            }
        }
    }

    @Test
    fun generatesFromARealModel() {
        if (!modelPresent()) return

        runBlocking {
            val loader = LiteRtLmModelLoader(cacheDir = cacheDir, systemPrompt = "You are terse.")
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
            val loader = LiteRtLmModelLoader(cacheDir = cacheDir)
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
