package io.github.lemcoder.koinference.runtime

/**
 * A connection that turns text into vectors.
 *
 * Separate from [GeneratingConnection] because the two share nothing past [Connection]: an embedding
 * model has no reply to stream, no parts, no tokens per second. An embedding "connection" is a thin
 * lifetime — open and close are cheap, there being no KV cache — but it is a [Connection] so that
 * embedding, generation and transcription models are all opened, held and torn down the same way.
 */
interface EmbeddingConnection : Connection {

    /** Length of the vectors this model produces; a caller sizing an index needs it up front. */
    val dimensions: Int

    /**
     * Embed [texts], in order. Batched because a model runs a batch in one pass and a caller
     * indexing documents has many; one text is a list of one. Whether the vectors are normalised,
     * and how they were pooled, is the backend's business and documented there.
     *
     * @throws KoinferenceException.ConnectionClosed if this connection is closed.
     * @throws KoinferenceException.GenerationFailed if embedding fails.
     */
    suspend fun embed(texts: List<String>): List<FloatArray>
}
