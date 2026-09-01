package io.github.lemcoder.koinference.executorch

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.backend.SamplingKnob
import io.github.lemcoder.koinference.runtime.Modality

/**
 * ExecuTorch, as something a [io.github.lemcoder.koinference.Koinference] can hold.
 *
 * Reads `.pte`, which nothing else here does, so registration order does not matter for it the way
 * it does for the two GGUF engines.
 */
object ExecuTorch : Backend {

    override val id: String = "executorch"

    /** Text out. `LlmModule` also declares vision and multimodal types, which this does not wire up. */
    override val modalities: Set<Modality> = setOf(Modality.TEXT)

    override fun handles(modelPath: String): Boolean = modelPath.endsWith(".pte")

    /**
     * Temperature and nothing else.
     *
     * The reachable `generate` overload takes temperature, sequence length and echo. Top-k, top-p,
     * min-p and the seed have nowhere to go, so they are not claimed — a benchmark that recorded a
     * seed here would be asserting a reproducibility this binding cannot give.
     */
    override val honours: Set<SamplingKnob> = setOf(SamplingKnob.TEMPERATURE)

    override fun loader(config: ModelConfig): ModelLoader = ExecuTorchModelLoader(config)
}
