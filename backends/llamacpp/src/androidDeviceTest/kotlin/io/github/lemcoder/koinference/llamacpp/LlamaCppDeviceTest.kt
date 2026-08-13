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

    private val modelPath = "/data/local/tmp/koinference/stories260K.gguf"

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
        if (!File(modelPath).isFile) return

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
        if (!File(modelPath).isFile) return // no model pushed; the other tests still cover loading

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
