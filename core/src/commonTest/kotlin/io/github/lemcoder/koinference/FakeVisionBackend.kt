package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.backend.SamplingKnob
import io.github.lemcoder.koinference.runtime.Modality

/**
 * A backend for a modality this repository has no engine for.
 *
 * The point of it: adding a second modality should be adding a backend, not reshaping the library.
 * This one is written as if it were real — its own `Modality`, its own runtime type, its own loader —
 * and what it needed from `:core` is the measure of whether the architecture holds.
 */
internal class FakeVisionBackend(
    override val id: String = "fake-diffusion",
    private val extensions: List<String> = listOf(".safetensors"),
) : Backend {

    val loaders = mutableListOf<FakeImageLoader>()

    override val honours: Set<SamplingKnob> = setOf(SamplingKnob.SEED)

    override val modalities: Set<Modality> = setOf(Modality.IMAGE)

    override fun handles(modelPath: String) = extensions.any { modelPath.endsWith(it) }

    override fun loader(config: ModelConfig): ModelLoader =
        FakeImageLoader(config).also { loaders += it }
}
