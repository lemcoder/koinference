package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.backend.ModelConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The model's own vocabulary, reached through the facade.
 *
 * Relationships rather than exact counts: the numbers belong to whichever model KOI_TEST_GGUF
 * points at. The same three properties the LiteRT-LM tokenizer test asserts, so that a token
 * count means the same kind of thing on both backends.
 */
class LlamaCppTokenizerTest {

    private val modelPath: String? = System.getenv("KOI_TEST_GGUF")

    @Test
    fun `counts tokens with the models vocabulary`() = runTest {
        val path = modelPath ?: return@runTest

        val loader = LlamaCppModelLoader(ModelConfig(contextTokens = 512))
        try {
            val runtime = loader.load(path) as LlamaCppTextRuntime

            val short = runtime.countTokens("Hello")
            val long = runtime.countTokens(
                "Hello, this is a considerably longer sentence with many more words in it.",
            )

            assertTrue(short > 0, "expected at least one token, got $short")
            assertTrue(long > short, "longer text should be more tokens: $long vs $short")
            assertEquals(short, runtime.countTokens("Hello"), "counting is not stable")

            val word = "internationalisation"
            assertTrue(
                runtime.countTokens(word) < word.length,
                "expected subword tokens, not characters",
            )
        } finally {
            loader.unload(path)
        }
    }
}
