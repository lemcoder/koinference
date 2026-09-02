package io.github.lemcoder.koinference.runtime

/**
 * What a backend's runtimes produce.
 *
 * Named for the *output*: a vision-language model reading an image and answering in words is
 * [TEXT], because `PromptPart.ImageFile` has always let a prompt carry pixels. [IMAGE] and [AUDIO]
 * are for models whose *reply* carries them.
 *
 * A set, not a single value, because a reply can carry more than one — models that interleave text
 * and audio in one response exist, and `setOf(TEXT, AUDIO)` is how a backend says so. What it does
 * *not* do is pick an interface: every generating runtime streams [ResponsePart], so this is what a
 * caller reads to know which parts a reply can contain, not a type selector.
 */
enum class Modality {
    TEXT,
    IMAGE,
    AUDIO,

    /**
     * Vectors rather than content.
     *
     * The odd one out, and deliberately here rather than in a type of its own: this enum answers
     * "what does asking this backend for something get you", and for an embedding model the answer
     * is numbers. A backend declaring this implements
     * [io.github.lemcoder.koinference.runtime.EmbeddingRuntime], not `GeneratingRuntime`.
     */
    EMBEDDING,
}
