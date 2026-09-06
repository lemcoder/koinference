package io.github.lemcoder.koinference.runtime

import io.github.lemcoder.koinference.ModelId

/**
 * Every failure the public API can produce, as one sealed family.
 *
 * One taxonomy, two delivery channels: a failure of a call you are awaiting is **thrown** (the
 * caller is right there); a failure that arrives with no call in flight — the engine process is
 * killed, the model is unloaded under you — is handed to the `onDeath` callback that
 * [io.github.lemcoder.koinference.Koinference.openConnection] took. Both speak these types, so a
 * caller does not learn two error vocabularies.
 *
 * Sealed so a `when` over it is exhaustive, and a Throwable so the call-scoped cases can just be
 * thrown. It is deliberately not a `Flow`/`Channel` of errors — see the public-API rule in
 * `CLAUDE.md`.
 */
sealed class KoinferenceException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** No registered backend reads the container, or the model file could not be loaded. */
    class LoadFailed(message: String, cause: Throwable? = null) :
        KoinferenceException(message, cause)

    /** The model loaded, but cannot do what this connection asked — e.g. generate on an embedder. */
    class Unsupported(message: String) : KoinferenceException(message)

    /** [Koinference.openConnection] was given a handle to a model that is not loaded. */
    class UnknownModel(id: ModelId) :
        KoinferenceException("No model is loaded for $id; it was never loaded or already unloaded")

    /** A generation or embedding call failed. Call-scoped: thrown from the call. */
    class GenerationFailed(message: String, cause: Throwable? = null) :
        KoinferenceException(message, cause)

    /** A call was made on a connection that has been closed. Call-scoped: thrown. */
    class ConnectionClosed(id: ModelId) :
        KoinferenceException("This connection to $id has been closed")

    /** The engine process backing a connection died with no call in flight. Out-of-band: `onDeath`. */
    class EngineDied(id: ModelId, cause: Throwable? = null) :
        KoinferenceException("The engine backing the connection to $id died", cause)

    /** The model was unloaded while this connection was still open. Out-of-band: `onDeath`. */
    class ModelUnloaded(id: ModelId) :
        KoinferenceException("The model $id was unloaded while a connection to it was open")
}
