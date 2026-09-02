package io.github.lemcoder.koinference.executorch

import io.github.lemcoder.koinference.backend.SamplingKnob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecuTorchBackendTest {

    @Test
    fun `claims pte and nothing else`() {
        assertTrue(ExecuTorch.handles("/m/llama.pte"))
        assertFalse(ExecuTorch.handles("/m/model.gguf"))
        assertFalse(ExecuTorch.handles("/m/model.litertlm"))
    }

    @Test
    fun `claims only temperature`() {
        // The reachable generate overload takes temperature, sequence length and echo. Claiming a
        // seed would assert a reproducibility this binding cannot give.
        assertEquals(setOf(SamplingKnob.TEMPERATURE), ExecuTorch.honours)
    }

    @Test
    fun `the id is the one published in results`() {
        assertEquals("executorch", ExecuTorch.id)
    }
}
