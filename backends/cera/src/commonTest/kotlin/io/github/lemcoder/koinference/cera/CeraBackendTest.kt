package io.github.lemcoder.koinference.cera

import io.github.lemcoder.koinference.backend.SamplingKnob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CeraBackendTest {

    @Test
    fun `claims gguf and nothing else`() {
        assertTrue(Cera.handles("/m/model.gguf"))
        assertFalse(Cera.handles("/m/model.litertlm"))
        assertFalse(Cera.handles("/m/model.task"))
    }

    @Test
    fun `declares only the knobs the binding passes down`() {
        // A claim, and a wrong one is invisible at run time: a results file would assert a
        // reproducibility the run never had. GenerateOpts carries the first four; the seed is on
        // SessionConfig, which is why it can be claimed at all.
        assertEquals(
            setOf(
                SamplingKnob.TEMPERATURE,
                SamplingKnob.TOP_K,
                SamplingKnob.TOP_P,
                SamplingKnob.MIN_P,
                SamplingKnob.SEED,
            ),
            Cera.honours,
        )
    }

    @Test
    fun `the id is the one published in results`() {
        assertEquals("cera", Cera.id)
    }
}
