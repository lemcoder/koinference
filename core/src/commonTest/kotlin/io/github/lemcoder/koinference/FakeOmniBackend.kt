package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.backend.SamplingKnob
import io.github.lemcoder.koinference.runtime.Modality

/**
 * A backend for a model that answers in text *and* audio, interleaved.
 *
 * Such models exist, and they are what an earlier design could not express: it split runtimes by
 * output type, so a reply was a `String` or an image but never a sequence of both. This is the probe
 * for the shape that replaced it — if a reply is a list of [io.github.lemcoder.koinference.runtime.ResponsePart],
 * an engine like this needs nothing from `:core` that a text engine does not.
 */
internal class FakeOmniBackend(
    override val id: String = "fake-omni",
    private val extensions: List<String> = listOf(".omni"),
) : Backend {

    val loaders = mutableListOf<FakeOmniLoader>()

    override val honours: Set<SamplingKnob> = setOf(SamplingKnob.TEMPERATURE, SamplingKnob.SEED)

    override val modalities: Set<Modality> = setOf(Modality.TEXT, Modality.AUDIO)

    override fun handles(modelPath: String) = extensions.any { modelPath.endsWith(it) }

    override fun loader(config: ModelConfig): ModelLoader =
        FakeOmniLoader(config).also { loaders += it }
}
