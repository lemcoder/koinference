package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.backend.SamplingKnob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlamaCppBackendTest {

    @Test
    fun `claims gguf and nothing else`() {
        assertTrue(LlamaCpp.handles("/m/model.gguf"))
        assertFalse(LlamaCpp.handles("/m/model.litertlm"))
        assertFalse(LlamaCpp.handles("/m/model.task"))
        assertFalse(LlamaCpp.handles("/m/model.tflite"))
    }

    @Test
    fun `declares only the knobs the facade applies`() {
        // koi_session_create takes no top-p and no seed. Claiming either would make a benchmark
        // record a reproducibility this engine cannot give.
        assertEquals(
            setOf(SamplingKnob.TOP_K, SamplingKnob.MIN_P, SamplingKnob.TEMPERATURE),
            LlamaCpp.honours,
        )
    }

    @Test
    fun `the host targets have nothing to refuse`() {
        // jvm, macOS, iOS and Linux link an archive built for the machine running it. Only the
        // Android AAR is one binary meeting hardware it was not built on, and this test does not
        // run there — LlamaCppDeviceTest asserts the Android answer on a real device.
        assertNull(LlamaCpp.unsupportedReason())
    }

    @Test
    fun `the id is the one published in results`() {
        assertEquals("llama.cpp", LlamaCpp.id)
    }
}
