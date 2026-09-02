package io.github.lemcoder.koinference.onnx.internal

/**
 * A slice of `bert-base-uncased`'s `vocab.txt`, with the real ids.
 *
 * Real ids matter: a tokenizer tested against a vocabulary invented for the test proves only that it
 * is self-consistent. These are the ids `bge-small-en-v1.5` and every other BERT-uncased model
 * assign, so a token id asserted here is the id the model will actually be fed.
 */
internal object BertVocabulary {

    val ids: Map<String, Int> = mapOf(
        "[PAD]" to 0,
        "[UNK]" to 100,
        "[CLS]" to 101,
        "[SEP]" to 102,
        "the" to 1996,
        "of" to 1997,
        "capital" to 3007,
        "france" to 2605,
        "is" to 2003,
        "paris" to 3000,
        "hello" to 7592,
        "world" to 2088,
        "embedding" to 7861,
        "," to 1010,
        "." to 1012,
        "?" to 1029,
        // "tokenization" is not one piece in this vocabulary; it is these three.
        "token" to 19204,
        "##ization" to 3989,
        "##s" to 2015,
        "un" to 4895,
        "##aff" to 21358,
        "##able" to 3085,
    )
}
