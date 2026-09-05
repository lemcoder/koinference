package io.github.lemcoder.koinference.runtime

/**
 * A loaded model that turns text into vectors.
 *
 * Separate from [GeneratingRuntime] because the two have nothing in common past [ModelRuntime]: an
 * embedding model has no reply to stream, no chunks, no tokens per second. Folding it into a
 * `ResponsePart` would call a vector a piece of a response and leave most of that interface dead.
 *
 * This is not the `EmbeddingRuntime` this repository deleted once. That one had no implementation —
 * a sealed hierarchy with a member nothing produced, which cost every caller a downcast that could
 * never fail. This one exists because a backend implements it.
 */
interface EmbeddingRuntime : ModelRuntime {

    /**
     * Length of the vectors this model produces.
     *
     * A property because a caller sizing an index needs it before embedding anything.
     */
    val dimensions: Int

    /**
     * Embeds [texts], in order.
     *
     * A list rather than one string because embedding is naturally batched — a model runs a batch
     * in one pass — and a caller indexing documents has many. One text is a list of one.
     *
     * What the vectors mean is the model's business: whether they are normalised, and how they were
     * pooled, belong to the backend and are documented there.
     */
    suspend fun embed(texts: List<String>): List<FloatArray>
}
