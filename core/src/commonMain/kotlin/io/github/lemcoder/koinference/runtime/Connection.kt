package io.github.lemcoder.koinference.runtime

/**
 * One usage of a loaded model: a decode context, opened over a model, used, then closed.
 *
 * This is what `ModelRuntime` was, split from the weights it ran on. Serialised — one call at a time
 * on a connection; parallel decode over several connections to one model is not offered (memory, and
 * the native contexts are not all thread-safe), so a caller who needs concurrency opens more
 * connections and accepts the per-context cost.
 *
 * Narrowed by the caller to what the model does — [GeneratingConnection], [EmbeddingConnection] —
 * exactly as the loader's one return type used to be. The base carries only teardown.
 *
 * There is no `markClosed`: a connection has one dead state, reached by [close]. Releasing the
 * weights is the model's job, not a connection's — which is the whole reason the two are separate.
 */
interface Connection {

    /** Whether [close] (or an out-of-band death) has torn this connection down. */
    val isClosed: Boolean

    /** Full teardown of this connection's context. Idempotent; other connections are unaffected. */
    suspend fun close()
}
