package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.backend.SamplingKnob
import io.github.lemcoder.koinference.runtime.Modality

/** A backend that hands out fake runtimes, so the entry point can be tested without an engine. */
internal class FakeBackend(
    override val id: String,
    private val extensions: List<String>,
    override val honours: Set<SamplingKnob> = emptySet(),
    override val modalities: Set<Modality> = setOf(Modality.TEXT),
    /** Non-null makes this backend one the device cannot run; see [Backend.unsupportedReason]. */
    private val unsupportedReason: String? = null,
) : Backend {

    val loaders = mutableListOf<FakeLoader>()

    override fun handles(modelPath: String) = extensions.any { modelPath.endsWith(it) }

    override fun unsupportedReason(): String? = unsupportedReason

    override fun loader(config: ModelConfig): ModelLoader = FakeLoader(config).also { loaders += it }
}
