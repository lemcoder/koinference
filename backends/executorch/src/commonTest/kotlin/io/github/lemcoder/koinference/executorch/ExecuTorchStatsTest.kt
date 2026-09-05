package io.github.lemcoder.koinference.executorch

import io.github.lemcoder.koinference.executorch.internal.ExecuTorchStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The engine's stats line is where this backend's token counts come from, so its parsing is worth
 * pinning: a malformed line must mean "no count", never a crash inside a callback on the engine's
 * own thread.
 */
class ExecuTorchStatsTest {

    /** The shape `extension/llm/runner/stats.h` writes: a flat object of numbers. */
    private val real = """{"prompt_tokens":22,"generated_tokens":31,"model_load_start_ms":1,""" +
        """"inference_end_ms":9,"first_token_ms":4}"""

    @Test
    fun `reads the generated token count`() {
        assertEquals(31, ExecuTorchStats.generatedTokens(real))
    }

    @Test
    fun `is not fooled by the prompt count next to it`() {
        // "prompt_tokens" appears first and is a different quantity.
        assertEquals(31, ExecuTorchStats.generatedTokens(real))
        assertEquals(7, ExecuTorchStats.generatedTokens("""{"prompt_tokens":99,"generated_tokens":7}"""))
    }

    @Test
    fun `tolerates whitespace`() {
        assertEquals(5, ExecuTorchStats.generatedTokens("""{ "generated_tokens" : 5 }"""))
    }

    @Test
    fun `says nothing rather than failing on a line it cannot read`() {
        assertNull(ExecuTorchStats.generatedTokens(""))
        assertNull(ExecuTorchStats.generatedTokens("not json"))
        assertNull(ExecuTorchStats.generatedTokens("""{"prompt_tokens":22}"""))
    }
}
