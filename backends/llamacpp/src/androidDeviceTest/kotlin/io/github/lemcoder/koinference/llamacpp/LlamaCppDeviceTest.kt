package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.llamacpp.internal.llamaBackendFree
import io.github.lemcoder.koinference.llamacpp.internal.llamaBackendInit
import io.github.lemcoder.koinference.llamacpp.internal.llamaGenerate
import io.github.lemcoder.koinference.llamacpp.internal.llamaModelFree
import io.github.lemcoder.koinference.llamacpp.internal.llamaModelLoad
import io.github.lemcoder.koinference.llamacpp.internal.llamaSessionCreate
import io.github.lemcoder.koinference.llamacpp.internal.llamaSessionFree
import io.github.lemcoder.koinference.llamacpp.internal.llamaSystemInfo
import java.io.File
import kotlin.test.Test
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
            assertTrue(llamaModelLoad("/data/local/tmp/does-not-exist.gguf") == 0L)
        } finally {
            llamaBackendFree()
        }
    }

    @Test
    fun generatesFromARealModel() {
        if (!File(modelPath).isFile) return // no model pushed; the other tests still cover loading

        llamaBackendInit()
        try {
            val model = llamaModelLoad(modelPath)
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
