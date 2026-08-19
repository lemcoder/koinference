package io.github.lemcoder.koinference.litertlm

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The model's own tokenizer, reached through the facade.
 *
 * Asserts relationships rather than exact counts, because the numbers belong to whichever model
 * KOI_TEST_LITERTLM points at: longer text is more tokens, the same text twice is the same
 * count, and a word is fewer tokens than its characters. A count that satisfied all three by
 * accident would be a working tokenizer.
 */
@OptIn(ExperimentalForeignApi::class)
class LiteRtLmTokenizerTest {

    private val modelPath: String? = getenv("KOI_TEST_LITERTLM")?.toKString()

    @Test
    fun countsTokensWithTheModelsTokenizer() {
        val path = modelPath ?: return

        runBlocking {
            val loader = LiteRtLmModelLoader()
            try {
                val runtime = loader.load(path)

                val short = runtime.countTokens("Hello")
                val long = runtime.countTokens(
                    "Hello, this is a considerably longer sentence with many more words in it.",
                )

                assertTrue(short > 0, "expected at least one token, got $short")
                assertTrue(long > short, "longer text should be more tokens: $long vs $short")
                assertEquals(short, runtime.countTokens("Hello"), "counting is not stable")

                // A tokenizer that returned characters would fail this; subword vocabularies
                // pack several characters per token.
                val word = "internationalisation"
                assertTrue(
                    runtime.countTokens(word) < word.length,
                    "expected subword tokens, not characters",
                )
            } finally {
                loader.unloadAll()
            }
        }
    }

    @Test
    fun countingAfterUnloadFails() {
        val path = modelPath ?: return

        runBlocking {
            val loader = LiteRtLmModelLoader()
            val runtime = loader.load(path)
            loader.unloadAll()

            // The engine is freed on unload; counting through it afterwards would be a
            // use-after-free rather than a wrong number.
            val failure = runCatching { runtime.countTokens("Hello") }.exceptionOrNull()
            assertTrue(failure is IllegalStateException, "expected a clear failure, got: $failure")
        }
    }
}
