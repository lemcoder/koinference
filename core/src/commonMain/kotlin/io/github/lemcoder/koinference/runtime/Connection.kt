package io.github.lemcoder.koinference.runtime

import io.github.lemcoder.koinference.ModelId
import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.prompt.promptOf

/**
 * One usage of a loaded model: a decoding context, opened over a [ModelId], used, then closed.
 *
 * This is what `ModelRuntime` was, split out from the weights it ran on. A model is loaded once
 * ([io.github.lemcoder.koinference.Koinference.loadModel]); a connection is opened over it
 * ([io.github.lemcoder.koinference.Koinference.openConnection]), possibly several times, and each is
 * torn down independently. The nesting matches the backend seam, where a session is already produced
 * by a model — this just exposes it.
 *
 * Serialised: one call at a time on a connection. Parallel decode over several connections to one
 * model is not offered (memory, and the native contexts are not all thread-safe) — a caller who
 * needs it opens more connections and accepts the KV-cache cost.
 *
 * **Streaming is a callback, not a `Flow`.** A KMP library is consumed from Swift and Java, where
 * `Flow` does not bridge; a Kotlin consumer who wants a `Flow` wraps [generate] with
 * `callbackFlow { }`. See the public-API rule in `CLAUDE.md`.
 */
interface Connection {

    /** The model this connection was opened over. */
    val modelId: ModelId

    /**
     * Generate a reply, delivering each [ResponsePart] to [onPart] as the backend produces it.
     *
     * Suspends until the reply is complete. The parts passed to [onPart] concatenate to the whole
     * reply, in order. A part is one backend step — a token for llama.cpp — so parts are events, not
     * tokens.
     *
     * @throws KoinferenceException.ConnectionClosed if this connection is closed.
     * @throws KoinferenceException.GenerationFailed if the generation itself fails.
     */
    suspend fun generate(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint? = null,
        onPart: (ResponsePart) -> Unit,
    )

    /** Full teardown of this connection's context. Idempotent; other connections are unaffected. */
    suspend fun close()

    /** Shorthand for a plain-text prompt. */
    suspend fun generate(prompt: String, onPart: (ResponsePart) -> Unit) =
        generate(promptOf(prompt), null, onPart)
}
