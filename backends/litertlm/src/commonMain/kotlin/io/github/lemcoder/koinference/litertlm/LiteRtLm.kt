package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.Backend
import io.github.lemcoder.koinference.ModelConfig
import io.github.lemcoder.koinference.ModelLoader
import io.github.lemcoder.koinference.SamplingKnob

/**
 * LiteRT-LM, as something a [io.github.lemcoder.koinference.BackendRegistry] can hold.
 *
 * Handles the two containers the runtime accepts. A raw `.tflite` is not one of them — LiteRT-LM
 * needs the tokenizer and metadata that only these carry.
 */
object LiteRtLm : Backend {

    override val id: String = "litert-lm"

    override fun handles(modelPath: String): Boolean =
        modelPath.endsWith(".litertlm") || modelPath.endsWith(".task")

    // LiteRT-LM's sampler has no min-p equivalent. Its seed is per engine rather than per
    // conversation, so two fresh engines with the same seed replay each other.
    override val honours: Set<SamplingKnob> = setOf(SamplingKnob.TOP_K, SamplingKnob.TOP_P, SamplingKnob.TEMPERATURE, SamplingKnob.SEED)

    override fun loader(config: ModelConfig): ModelLoader = LiteRtLmModelLoader(config)
}
