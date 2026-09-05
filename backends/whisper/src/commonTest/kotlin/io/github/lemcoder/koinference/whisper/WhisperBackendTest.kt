package io.github.lemcoder.koinference.whisper

import io.github.lemcoder.koinference.runtime.Modality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WhisperBackendTest {

    @Test
    fun `claims ggml whisper models and nothing else`() {
        assertTrue(Whisper.handles("/m/ggml-tiny.bin"))
        assertTrue(Whisper.handles("/m/ggml-base.en.bin"))
        // A bare .bin is somebody else's: the prefix is what distinguishes a whisper model.
        assertFalse(Whisper.handles("/m/tokenizer.bin"))
        assertFalse(Whisper.handles("/m/model.gguf"))
        assertFalse(Whisper.handles("/m/model.pte"))
    }

    @Test
    fun `claims no sampling knobs at all`() {
        // whisper decodes greedily or with beam search; there is no top-k, top-p, min-p or seed to
        // apply, and claiming one would put a false reproducibility in a results file.
        assertEquals(emptySet(), Whisper.honours)
    }

    @Test
    fun `is a text engine, because modality is named for the output`() {
        // It reads audio. What it produces is words, and that is what Modality describes — the same
        // reason a vision-language model is TEXT.
        assertEquals(setOf(Modality.TEXT), Whisper.modalities)
    }

    @Test
    fun `the id is the one published in results`() {
        assertEquals("whisper.cpp", Whisper.id)
    }
}
