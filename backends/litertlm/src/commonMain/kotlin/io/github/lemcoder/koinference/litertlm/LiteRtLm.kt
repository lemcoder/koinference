package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.Koinference
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.backend.SamplingKnob
import io.github.lemcoder.koinference.runtime.Modality

/**
 * LiteRT-LM, as something a [io.github.lemcoder.koinference.Koinference] can hold.
 *
 * Handles the two containers the runtime accepts. A raw `.tflite` is not one of them — LiteRT-LM
 * needs the tokenizer and metadata that only these carry.
 */
object LiteRtLm : Backend {

    override val id: String = "litert-lm"

    /** Text out. A GGUF vision-language model still answers in words; see Modality. */
    override val modalities: Set<Modality> = setOf(Modality.TEXT)

    override fun handles(modelPath: String): Boolean =
        modelPath.endsWith(".litertlm") || modelPath.endsWith(".task")

    // LiteRT-LM's sampler has no min-p equivalent. Its seed is per engine rather than per
    // conversation, so two fresh engines with the same seed replay each other.
    override val honours: Set<SamplingKnob> = setOf(SamplingKnob.TOP_K, SamplingKnob.TOP_P, SamplingKnob.TEMPERATURE, SamplingKnob.SEED)

    override fun loader(config: ModelConfig): ModelLoader = LiteRtLmModelLoader(config)
}
