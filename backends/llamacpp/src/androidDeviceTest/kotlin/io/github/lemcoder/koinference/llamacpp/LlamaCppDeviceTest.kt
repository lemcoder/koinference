package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.llamacpp.internal.ModelOptions
import io.github.lemcoder.koinference.llamacpp.internal.SessionOptions
import io.github.lemcoder.koinference.llamacpp.internal.platformBridge
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lemcoder.koinference.runtime.GenerationParameters
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Runs on a device or emulator, which is the only place the packaged `.so` is loaded by ART and
 * llama.cpp actually executes on the target ABI. Everything the AAR check proves is structural.
 *
 * The model is pushed to the device by CI (see the Android job); the test skips when it is absent so
 * a local `connectedAndroidTest` without it still reports something useful.
 */
class LlamaCppDeviceTest {

    /**
     * The model to generate from, and whether its absence is allowed to pass.
     *
     * Passed explicitly, a missing file fails the test. It used to skip, which meant a run
     * reported four passes while never loading a model — the difference between "the engine
     * generates on this device" and "the file was not where I looked" is the whole point of a
     * device test.
     *
     * Push it somewhere the app can *write*, not just read. /data/local/tmp is shell's: an app
     * can open a model there but not create the delegate's weight cache beside it, which costs
     * seconds of setup and gigabytes of RSS on every load.
     *
     *     adb push model.gguf /sdcard/Android/data/io.github.lemcoder.koinference.llamacpp.test/files/
     *     ./gradlew :backends:llamacpp:connectedAndroidDeviceTest \
     *         -Pandroid.testInstrumentationRunnerArguments.ggufModel=/sdcard/Android/data/io.github.lemcoder.koinference.llamacpp.test/files/model.gguf
     */
    private val requestedModel: String? =
        InstrumentationRegistry.getArguments().getString("ggufModel")

    private val modelPath: String =
        requestedModel ?: "/data/local/tmp/koinference/stories260K.gguf"

    /** Null when the test should run, or a reason to skip when no model was asked for. */
    private fun skipReason(): String? = when {
        File(modelPath).isFile -> null
        requestedModel != null -> throw AssertionError(
            "ggufModel was set to $modelPath but no readable file is there. Push the model, " +
                "or drop the argument to skip these tests.",
        )

        else -> "no model at the default path and none requested"
    }

    /**
     * The generated JNI bridges reach the packaged `.so` and it runs on this ABI.
     *
     * Needs no model: the failure path crosses the boundary in both directions, which is what
     * would break if the stub were missing or built for the wrong architecture.
     */
    @Test
    fun missingModelFailsThroughTheBridge() {
        val failure = assertFailsWith<IllegalStateException> {
            platformBridge().openModel(ModelOptions("/data/local/tmp/does-not-exist.gguf"))
        }
        assertTrue(failure.message!!.contains("does-not-exist.gguf"))
    }

    @Test
    fun generatesThroughTheLoader() {
        if (skipReason() != null) return

        // The bridge test below proves the .so runs; this proves the public API reaches it —
        // the loader owns backend init and session creation, which the bridge test does by hand.
        runBlocking {
            val loader = LlamaCppModelLoader(ModelConfig(contextTokens = 256, maxOutputTokens = 16))
            val runtime = loader.load(modelPath)
            try {
                assertIs<LlamaCppTextRuntime>(runtime)
                val reply = runtime.generateResponse("Once upon a time").text()
                assertTrue(reply.isNotBlank(), "expected generated text, got: '$reply'")
            } finally {
                loader.unload(modelPath)
            }
        }
    }

    /**
     * Streaming arrives in pieces, and those pieces are the reply.
     *
     * The equivalent LiteRT-LM test exists because that binding could have chosen cumulative
     * chunks; this one exists because nothing on this leg had ever checked streaming on a device
     * at all. Both assert more than one chunk: a single chunk delivered at the end satisfies
     * every other property of a stream while making time to first token equal to total latency.
     */
    @Test
    fun streamedChunksConcatenateToTheBlockingReply() {
        if (skipReason() != null) return

        runBlocking {
            val loader = LlamaCppModelLoader(ModelConfig(contextTokens = 256, maxOutputTokens = 24))
            val runtime = loader.load(modelPath)
            try {
                // Greedy, so the two calls answer identically rather than by luck.
                runtime.updateGenerationParameters(GenerationParameters(temperature = 0.0))

                val streamed = runtime.streamResponse("Once upon a time").toList().textParts()
                val blocking = runtime.generateResponse("Once upon a time").text()

                Log.i(
                    "koinference-benchmark",
                    "llama.cpp streaming: chunks=${streamed.size} " +
                        "streamedChars=${streamed.sumOf { it.length }} blockingChars=${blocking.length}",
                )

                assertTrue(streamed.size > 1, "expected several chunks, got ${streamed.size}")
                assertEquals(blocking, streamed.joinToString(""))
            } finally {
                loader.unload(modelPath)
            }
        }
    }

    /** The same thing one layer down, so a failure says whether the seam or the runtime broke. */
    @Test
    fun generatesFromARealModelThroughTheBridge() {
        if (skipReason() != null) return

        val model = platformBridge().openModel(ModelOptions(modelPath))
        try {
            val session = model.openSession(
                SessionOptions(nCtx = 256, nThreads = 2, nPredict = 16, temperature = 0f, topK = 1, minP = 0f),
            )
            try {
                val text = session.generate(null, "Once upon a time", null)
                assertTrue(text.isNotEmpty(), "expected the model to produce tokens")
                assertTrue(session.tokenCount("Once upon a time") > 0)
            } finally {
                session.close()
            }
        } finally {
            model.close()
        }
    }
}
