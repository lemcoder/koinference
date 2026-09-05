package io.github.lemcoder.koinference.onnx.internal

/**
 * BERT's WordPiece tokenizer, in Kotlin.
 *
 * Every embedding model this backend targets is BERT-shaped and ships a `vocab.txt`, and the
 * alternatives were worse: DJL's tokenizers publish no Android natives, and ONNX Runtime Extensions
 * only registers custom ops for graphs that already contain a tokenizer, which stock exports do not.
 * So this is the same call as `WavAudio` in `:backends:whisper` — parsing and table lookup are not a
 * reason to write C, and doing it here means it can be tested against known token ids with no model
 * present.
 *
 * The algorithm is HuggingFace's `BertTokenizer` with `do_lower_case`: strip control characters and
 * accents, put whitespace around CJK and punctuation, split on whitespace, then greedy
 * longest-match-first against the vocabulary, continuations prefixed with `##`.
 *
 * What it is not: a general tokenizer. There is no BPE here and no `tokenizer.json` parsing, because
 * the models this backend loads do not need them, and a half-implemented general tokenizer is worse
 * than an honest specific one.
 */
internal object WordPiece {

    /** What the graph takes: parallel arrays, already padded to one length across a batch. */
    data class Encoding(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray,
    ) {
        // Hand-written because ByteArray-style equality applies to LongArray too: the generated one
        // would compare references and call two identical encodings different.
        override fun equals(other: Any?): Boolean = other is Encoding &&
            inputIds.contentEquals(other.inputIds) &&
            attentionMask.contentEquals(other.attentionMask) &&
            tokenTypeIds.contentEquals(other.tokenTypeIds)

        override fun hashCode(): Int = inputIds.contentHashCode()
    }

    const val CLS = "[CLS]"
    const val SEP = "[SEP]"
    const val PAD = "[PAD]"
    const val UNK = "[UNK]"

    /** Word pieces after the first carry this, which is what makes the match greedy-from-the-left. */
    private const val CONTINUATION = "##"

    /**
     * Tokenizes [text] to vocabulary ids, wrapped in `[CLS]` … `[SEP]`.
     *
     * [maxTokens] counts the special tokens, because the model's position embeddings do: a 512-limit
     * model takes 510 pieces of text. Longer input is truncated rather than rejected — that is what
     * the reference tokenizer does, and an embedding of the first 510 tokens is more useful to a
     * caller than an exception.
     */
    fun encode(text: String, vocabulary: Map<String, Int>, maxTokens: Int): List<Int> {
        val cls = vocabulary[CLS] ?: error("vocabulary has no $CLS; is this a BERT vocab.txt?")
        val sep = vocabulary[SEP] ?: error("vocabulary has no $SEP; is this a BERT vocab.txt?")
        val unknown = vocabulary[UNK] ?: error("vocabulary has no $UNK; is this a BERT vocab.txt?")

        val pieces = mutableListOf<Int>()
        for (word in split(text)) {
            if (pieces.size >= maxTokens - 2) break
            pieces += wordPieces(word, vocabulary, unknown)
        }

        val body = pieces.take(maxTokens - 2)
        return buildList {
            add(cls)
            addAll(body)
            add(sep)
        }
    }

    /**
     * Greedy longest-match-first over one word.
     *
     * The whole word becomes `[UNK]` when any piece of it fails to match, rather than the failing
     * piece alone — the reference implementation does this, and the difference shows up on rare
     * characters, so it is not a detail to improvise.
     */
    private fun wordPieces(word: String, vocabulary: Map<String, Int>, unknown: Int): List<Int> {
        if (word.isEmpty()) return emptyList()

        val pieces = mutableListOf<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var matched: Int? = null
            while (start < end) {
                val piece = if (start == 0) word.substring(start, end) else CONTINUATION + word.substring(start, end)
                val id = vocabulary[piece]
                if (id != null) {
                    matched = id
                    break
                }
                end--
            }
            if (matched == null) return listOf(unknown)
            pieces += matched
            start = end
        }
        return pieces
    }

    /**
     * Lower-cases, drops accents, and separates punctuation and CJK into their own tokens.
     *
     * Accent stripping is by Unicode decomposition in the reference; here it is a table for the
     * Latin-1 range, which covers the languages these vocabularies actually contain. A character it
     * does not know is left alone and becomes `[UNK]` if the vocabulary lacks it — visible in the
     * ids rather than silently wrong.
     */
    internal fun split(text: String): List<String> {
        val cleaned = StringBuilder()
        for (character in text) {
            when {
                character.code == 0 || character.code == 0xfffd -> Unit
                character.isISOControl() -> cleaned.append(' ')
                character.isWhitespace() -> cleaned.append(' ')
                isPunctuation(character) || isCjk(character) -> {
                    cleaned.append(' ').append(stripAccent(character.lowercaseChar())).append(' ')
                }
                else -> cleaned.append(stripAccent(character.lowercaseChar()))
            }
        }
        return cleaned.toString().split(' ').filter { it.isNotEmpty() }
    }

    private fun stripAccent(character: Char): Char = ACCENTS[character] ?: character

    /** ASCII punctuation plus the Unicode punctuation categories, as the reference defines it. */
    private fun isPunctuation(character: Char): Boolean {
        val code = character.code
        if (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) return true
        return when (character.category) {
            CharCategory.CONNECTOR_PUNCTUATION, CharCategory.DASH_PUNCTUATION,
            CharCategory.START_PUNCTUATION, CharCategory.END_PUNCTUATION,
            CharCategory.INITIAL_QUOTE_PUNCTUATION, CharCategory.FINAL_QUOTE_PUNCTUATION,
            CharCategory.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    /** CJK characters are one token each, which is how the vocabulary was built. */
    private fun isCjk(character: Char): Boolean = character.code in 0x4E00..0x9FFF ||
        character.code in 0x3400..0x4DBF ||
        character.code in 0xF900..0xFAFF

    private val ACCENTS: Map<Char, Char> = buildMap {
        "àáâãäå".forEach { put(it, 'a') }
        "èéêë".forEach { put(it, 'e') }
        "ìíîï".forEach { put(it, 'i') }
        "òóôõö".forEach { put(it, 'o') }
        "ùúûü".forEach { put(it, 'u') }
        put('ç', 'c'); put('ñ', 'n'); put('ý', 'y')
    }
}
