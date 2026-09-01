package io.github.lemcoder.koinference.executorch

import io.github.lemcoder.koinference.executorch.internal.FakeSystemFiles
import io.github.lemcoder.koinference.executorch.internal.TokenizerFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ExecuTorch keeps the vocabulary out of the `.pte`, so the backend has to find it.
 *
 * A convention rather than a `ModelConfig` field, and conventions are exactly the thing to pin down:
 * this is the only reason a model that exists still fails to load.
 */
class TokenizerFileTest {

    @Test
    fun `takes the tokenizer named after the model before a bare one`() {
        val files = FakeSystemFiles(setOf("/m/llama.tokenizer.model", "/m/tokenizer.model"))

        // Two exported models in one directory must not be handed the same vocabulary.
        assertEquals("/m/llama.tokenizer.model", TokenizerFile.beside("/m/llama.pte", files))
    }

    @Test
    fun `falls back to the bare name`() {
        val files = FakeSystemFiles(setOf("/m/tokenizer.model"))

        assertEquals("/m/tokenizer.model", TokenizerFile.beside("/m/llama.pte", files))
    }

    @Test
    fun `accepts the other names ExecuTorch's examples produce`() {
        assertEquals(
            "/m/tokenizer.json",
            TokenizerFile.beside("/m/x.pte", FakeSystemFiles(setOf("/m/tokenizer.json"))),
        )
        assertEquals(
            "/m/tokenizer.bin",
            TokenizerFile.beside("/m/x.pte", FakeSystemFiles(setOf("/m/tokenizer.bin"))),
        )
    }

    @Test
    fun `finds nothing when nothing is there`() {
        assertNull(TokenizerFile.beside("/m/x.pte", FakeSystemFiles(setOf("/m/x.pte"))))
    }

    @Test
    fun `says what it looked for`() {
        // The failure message is the whole value of this path: a missing tokenizer otherwise
        // crashes inside LlmModule's constructor, in native code, with nothing to read.
        val searched = TokenizerFile.searched("/m/llama.pte")

        assertTrue(searched.contains("/m/llama.tokenizer.model"))
        assertTrue(searched.contains("/m/tokenizer.model"))
    }
}
