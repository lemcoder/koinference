package io.github.lemcoder.koinference.runtime

/**
 * Everything a loaded text model can do, as one type.
 *
 * Exists so that [io.github.lemcoder.koinference.backend.ModelLoader.load] returns something a
 * caller can generate with. It used to return [ModelRuntime], which carries the settings but not
 * `generateResponse`, so every caller cast — the same hedge the deleted embedding runtime was, in
 * a different place: an abstraction keeping room for a case that does not exist.
 *
 * If an embedding backend is ever added this is where it shows: `load` would have to widen, or the
 * registry would need a typed variant. That is a better problem than the cast, because it only
 * arrives with the code that needs it.
 */
interface TextModelRuntime : ModelRuntime, TextRuntime, StreamingTextRuntime, TokenCounting
