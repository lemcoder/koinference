package io.github.lemcoder.koinference.onnx

import io.github.lemcoder.koinference.onnx.internal.BertVocabulary
import io.github.lemcoder.koinference.onnx.internal.WordPiece
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tokenizer, against the ids BERT actually assigns.
 *
 * This is the part of the backend most able to fail quietly: a wrong id is a valid id, and the model
 * will happily embed nonsense. Nothing here needs a model, a graph, or a device.
 */
class WordPieceTest {

    private val vocabulary = BertVocabulary.ids

    private fun encode(text: String, maxTokens: Int = 512) =
        WordPiece.encode(text, vocabulary, maxTokens)

    @Test
    fun `wraps the sentence in CLS and SEP`() {
        // The ids HuggingFace's BertTokenizer produces for this sentence.
        assertEquals(listOf(101, 7592, 2088, 102), encode("hello world"))
    }

    @Test
    fun `lower-cases`() {
        assertEquals(encode("hello world"), encode("Hello World"))
        assertEquals(encode("hello world"), encode("HELLO WORLD"))
    }

    @Test
    fun `splits punctuation into its own token`() {
        // "world." is three pieces, not one unknown word.
        assertEquals(listOf(101, 7592, 2088, 1012, 102), encode("hello world."))
        assertEquals(listOf(101, 7592, 1010, 2088, 102), encode("hello, world"))
    }

    @Test
    fun `breaks a word into pieces, continuations marked`() {
        // token + ##ization + ##s, which is what makes this WordPiece rather than a word lookup.
        assertEquals(listOf(101, 19204, 3989, 2015, 102), encode("tokenizations"))
        assertEquals(listOf(101, 4895, 21358, 3085, 102), encode("unaffable"))
    }

    @Test
    fun `a word it cannot piece together is one unknown, not several`() {
        // The reference marks the whole word unknown when any piece fails; the difference shows up
        // on rare characters, so it is worth pinning.
        assertEquals(listOf(101, 100, 102), encode("qqqzzz"))
    }

    @Test
    fun `strips accents`() {
        assertEquals(encode("france"), encode("fránce"))
    }

    @Test
    fun `collapses whitespace of every kind`() {
        assertEquals(encode("hello world"), encode("  hello\t\nworld  "))
    }

    @Test
    fun `truncates to the model's limit, special tokens included`() {
        val long = List(600) { "hello" }.joinToString(" ")

        val ids = encode(long, maxTokens = 16)

        // 16 total: [CLS] + 14 pieces + [SEP]. A model's position embeddings count the specials too.
        assertEquals(16, ids.size)
        assertEquals(101, ids.first())
        assertEquals(102, ids.last())
        assertTrue(ids.drop(1).dropLast(1).all { it == 7592 })
    }

    @Test
    fun `an empty string is just the special tokens`() {
        assertEquals(listOf(101, 102), encode(""))
    }
}
