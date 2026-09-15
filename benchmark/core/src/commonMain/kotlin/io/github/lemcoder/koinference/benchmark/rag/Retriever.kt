package io.github.lemcoder.koinference.benchmark.rag

import kotlin.math.sqrt

/**
 * Deterministic top-k retrieval over a fixed corpus, by cosine similarity.
 *
 * The RAG axis measures the *speed* cost of retrieval, not its quality, so the corpus and the
 * query are fixed and the ranking is pure — no index, no randomness, same result every run. The
 * embeddings come from the on-device encoder (the ONNX backend); this class only ranks them and
 * builds the augmented prompt, which is why it is testable without a model.
 */
class Retriever(private val corpus: List<Passage>) {

    /** A corpus entry: its text and the vector the encoder produced for it. */
    class Passage(val id: String, val text: String, val embedding: FloatArray)

    /** A ranked hit: the passage and its similarity to the query, in [-1, 1]. */
    class Hit(val passage: Passage, val score: Float)

    /**
     * The [k] passages most similar to [queryEmbedding], most similar first. Ties break by corpus
     * order so the result is stable. A [k] of 0 or a negative retrieves nothing.
     */
    fun topK(queryEmbedding: FloatArray, k: Int): List<Hit> {
        if (k <= 0 || corpus.isEmpty()) return emptyList()
        return corpus
            .map { Hit(it, cosineSimilarity(queryEmbedding, it.embedding)) }
            .sortedByDescending { it.score }
            .take(k)
    }

    /**
     * The prompt with the retrieved passages prepended as context, in rank order. The plain query
     * is returned unchanged when nothing was retrieved, so an OFF workload and an ON workload that
     * happened to retrieve nothing are the same prompt — as they should be.
     */
    fun augment(query: String, hits: List<Hit>): String {
        if (hits.isEmpty()) return query
        val context = hits.joinToString("\n\n") { it.passage.text }
        return "Context:\n$context\n\nQuestion: $query"
    }
}

/**
 * Cosine similarity of two equal-length vectors. Returns 0 when either is a zero vector — there is
 * no angle to a point at the origin, and 0 (orthogonal) is the honest neutral rank.
 */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "vectors differ in length: ${a.size} vs ${b.size}" }
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
        dot += a[i] * b[i]
        na += a[i] * a[i]
        nb += b[i] * b[i]
    }
    if (na == 0.0 || nb == 0.0) return 0f
    return (dot / (sqrt(na) * sqrt(nb))).toFloat()
}
