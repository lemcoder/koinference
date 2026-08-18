package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.llamacpp.internal.llamaBackendFree
import io.github.lemcoder.koinference.llamacpp.internal.llamaBackendInit
import io.github.lemcoder.koinference.llamacpp.internal.llamaGenerate
import io.github.lemcoder.koinference.llamacpp.internal.llamaModelFree
import io.github.lemcoder.koinference.llamacpp.internal.llamaModelLoad
import io.github.lemcoder.koinference.llamacpp.internal.llamaSessionCreate
import io.github.lemcoder.koinference.llamacpp.internal.llamaSessionFree
import io.github.lemcoder.koinference.llamacpp.internal.llamaSystemInfo
import kotlinx.coroutines.runBlocking
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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

    @Test
    fun backendReportsSystemInfo() {
        llamaBackendInit()
        try {
            val info = llamaSystemInfo()
            assertTrue(info.isNotEmpty(), "expected a non-empty system info string")
        } finally {
            llamaBackendFree()
        }
    }

    @Test
    fun missingModelReturnsNullHandle() {
        llamaBackendInit()
        try {
            assertTrue(llamaModelLoad("/data/local/tmp/does-not-exist.gguf", nGpuLayers = 0) == 0L)
        } finally {
            llamaBackendFree()
        }
    }

    @Test
    fun generatesThroughTheLoader() {
        if (skipReason() != null) return

        // The bridge test below proves the .so runs; this proves the public API reaches it —
        // the loader owns backend init and session creation, which the bridge test does by hand.
        runBlocking {
            val loader = LlamaCppModelLoader(nCtx = 256, nPredict = 16)
            val runtime = loader.load(modelPath)
            try {
                assertIs<LlamaCppTextRuntime>(runtime)
                val reply = runtime.generateResponse("Once upon a time")
                assertTrue(reply.isNotBlank(), "expected generated text, got: '$reply'")
            } finally {
                loader.unload(modelPath)
            }
        }
    }

    @Test
    fun generatesFromARealModel() {
        if (skipReason() != null) return

        llamaBackendInit()
        try {
            val model = llamaModelLoad(modelPath, nGpuLayers = 0)
            assertNotEquals(0L, model, "failed to load $modelPath")
            try {
                val session = llamaSessionCreate(
                    modelHandle = model,
                    nCtx = 256,
                    nThreads = 2,
                    nPredict = 16,
                    temp = 0f,
                    topK = 1,
                    minP = 0f,
                )
                assertNotEquals(0L, session, "failed to create a session")
                try {
                    val text = llamaGenerate(session, null, "Once upon a time", null)
                    assertTrue(text.isNotEmpty(), "expected the model to produce tokens")
                } finally {
                    llamaSessionFree(session)
                }
            } finally {
                llamaModelFree(model)
            }
        } finally {
            llamaBackendFree()
        }
    }
}
