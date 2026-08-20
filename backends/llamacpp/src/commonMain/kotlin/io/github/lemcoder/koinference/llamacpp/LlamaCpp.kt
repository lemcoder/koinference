package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.backend.BackendRegistry
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.backend.SamplingKnob

/**
 * llama.cpp, as something a [io.github.lemcoder.koinference.BackendRegistry] can hold.
 *
 * Register it to make GGUF loadable without naming [LlamaCppModelLoader] at the call site. The
 * loader stays public for a caller that wants this engine specifically.
 */
object LlamaCpp : Backend {

    override val id: String = "llama.cpp"

    override fun handles(modelPath: String): Boolean = modelPath.endsWith(".gguf")

    // koi_session_create takes no top-p and no seed, so a caller setting either is ignored
    // rather than surprised by another knob standing in for it.
    override val honours: Set<SamplingKnob> = setOf(SamplingKnob.TOP_K, SamplingKnob.MIN_P, SamplingKnob.TEMPERATURE)

    override fun loader(config: ModelConfig): ModelLoader = LlamaCppModelLoader(config)
}
