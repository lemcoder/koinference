package io.github.lemcoder.koinference

/**
 * Sampling knobs, all optional: null leaves the backend's own default in place rather than
 * imposing one here, so a value that appears in this type has been asked for.
 *
 * Not every backend has every knob. A backend must document which of these it ignores; it
 * must not silently reinterpret one as another (min-p is not top-p).
 *
 * @param topK        Sample from the k most likely tokens. Honoured by every backend.
 * @param minP        Minimum probability relative to the top token. llama.cpp only;
 *                    LiteRT-LM's sampler has no equivalent and ignores it.
 * @param topP        Nucleus sampling threshold. LiteRT-LM only.
 * @param temperature Logit temperature. Honoured by every backend.
 * @param seed        Sampler seed. Fixing it makes a run reproducible. LiteRT-LM only;
 *                    llama.cpp's facade does not expose one.
 */
data class GenerationParameters(
    val topK: Int? = null,
    val minP: Double? = null,
    val topP: Double? = null,
    val temperature: Double? = null,
    val seed: Int? = null,
)
