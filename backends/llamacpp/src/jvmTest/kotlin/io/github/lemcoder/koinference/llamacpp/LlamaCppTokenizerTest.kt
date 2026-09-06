package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.runtime.text.TokenCounting
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlamaCppTokenizerTest {

    private val modelPath: String? = System.getenv("KOI_TEST_GGUF")

    @Test
    fun `counts tokens with the models vocabulary`() = runTest {
        val path = modelPath ?: return@runTest
        val loader = LlamaCppModelLoader(ModelConfig(contextTokens = 512))
        val conn = loader.load(path).open()
        val counter = conn as TokenCounting
        try {
            val short = counter.countTokens("Hello")
            val long = counter.countTokens(
                "Hello, this is a considerably longer sentence with many more words in it.")
            assertTrue(short > 0, "expected at least one token, got $short")
            assertTrue(long > short, "longer text should be more tokens: $long vs $short")
            assertEquals(short, counter.countTokens("Hello"), "counting is not stable")
            val word = "internationalisation"
            assertTrue(counter.countTokens(word) < word.length, "expected subword tokens, not characters")
        } finally {
            conn.close(); loader.unload(path)
        }
    }
}
