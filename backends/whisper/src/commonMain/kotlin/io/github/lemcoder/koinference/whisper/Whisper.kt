package io.github.lemcoder.koinference.whisper

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.backend.SamplingKnob
import io.github.lemcoder.koinference.runtime.Modality

/**
 * whisper.cpp, as something a [io.github.lemcoder.koinference.Koinference] can hold.
 *
 * The first backend here that takes audio in. Its models are ggml `.bin` files, which nothing else
 * claims.
 */
object Whisper : Backend {

    override val id: String = "whisper.cpp"

    /**
     * Text out, and that is the whole of it.
     *
     * [Modality] is named for the output, so an engine that *reads* audio and answers in words is
     * `TEXT` — the same reason a vision-language model is. What a backend accepts is `PromptPart`'s
     * business.
     */
    override val modalities: Set<Modality> = setOf(Modality.TEXT)

    override fun handles(modelPath: String): Boolean =
        modelPath.endsWith(".bin") && modelPath.substringAfterLast('/').startsWith("ggml-")

    /**
     * Nothing.
     *
     * whisper decodes with greedy or beam search; temperature exists as a fallback ladder rather
     * than a knob, and there is no top-k, top-p, min-p or seed to apply. An empty set is the honest
     * answer, and it is what stops a results file claiming this engine honoured a seed.
     */
    override val honours: Set<SamplingKnob> = emptySet()

    override fun loader(config: ModelConfig): ModelLoader = WhisperModelLoader(config)
}
