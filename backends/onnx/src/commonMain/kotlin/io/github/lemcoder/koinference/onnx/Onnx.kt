package io.github.lemcoder.koinference.onnx

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.backend.SamplingKnob
import io.github.lemcoder.koinference.runtime.Modality

/**
 * ONNX Runtime, as something a [io.github.lemcoder.koinference.Koinference] can hold.
 *
 * The embedding leg. It produces vectors rather than replies, so what it loads is an
 * [OnnxEmbeddingRuntime] and a caller narrows to
 * [io.github.lemcoder.koinference.runtime.EmbeddingRuntime] rather than to `GeneratingRuntime`.
 */
object Onnx : Backend {

    override val id: String = "onnx"

    override val modalities: Set<Modality> = setOf(Modality.EMBEDDING)

    override fun handles(modelPath: String): Boolean = modelPath.endsWith(".onnx")

    /** An encoder has no sampler: nothing to claim, and claiming anything would be a lie. */
    override val honours: Set<SamplingKnob> = emptySet()

    override fun loader(config: ModelConfig): ModelLoader = OnnxModelLoader(config)
}
