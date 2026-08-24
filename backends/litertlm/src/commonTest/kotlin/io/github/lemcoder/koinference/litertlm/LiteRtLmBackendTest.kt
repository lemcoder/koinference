package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.backend.SamplingKnob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiteRtLmBackendTest {

    @Test
    fun claimsBothContainersButNotRawTflite() {
        assertTrue(LiteRtLm.handles("/m/model.litertlm"))
        assertTrue(LiteRtLm.handles("/m/model.task"))
        // The runtime rejects it: weights alone carry no tokenizer or metadata.
        assertFalse(LiteRtLm.handles("/m/model.tflite"))
        assertFalse(LiteRtLm.handles("/m/model.gguf"))
    }

    @Test
    fun declaresOnlyTheKnobsItsSamplerApplies() {
        // No min-p equivalent in LiteRT-LM's sampler; it is dropped rather than passed as top-p.
        assertEquals(
            setOf(
                SamplingKnob.TOP_K,
                SamplingKnob.TOP_P,
                SamplingKnob.TEMPERATURE,
                SamplingKnob.SEED,
            ),
            LiteRtLm.honours,
        )
    }

    @Test
    fun theIdIsTheOnePublishedInResults() {
        assertEquals("litert-lm", LiteRtLm.id)
    }
}
