package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.Koinference
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.backend.SamplingKnob
import io.github.lemcoder.koinference.llamacpp.internal.llamaCppUnsupportedReason
import io.github.lemcoder.koinference.runtime.Modality

/**
 * llama.cpp, as something a [io.github.lemcoder.koinference.Koinference] can hold.
 *
 * Register it to make GGUF loadable without naming [LlamaCppModelLoader] at the call site. The
 * loader stays public for a caller that wants this engine specifically.
 */
object LlamaCpp : Backend {

    override val id: String = "llama.cpp"

    /** Text out. A GGUF vision-language model still answers in words; see Modality. */
    override val modalities: Set<Modality> = setOf(Modality.TEXT)

    override fun handles(modelPath: String): Boolean = modelPath.endsWith(".gguf")

    // koi_session_create takes no top-p and no seed, so a caller setting either is ignored
    // rather than surprised by another knob standing in for it.
    override val honours: Set<SamplingKnob> = setOf(SamplingKnob.TOP_K, SamplingKnob.MIN_P, SamplingKnob.TEMPERATURE)

    /**
     * Android only, and it can refuse a device the AAR was installed on.
     *
     * See [llamaCppUnsupportedReason]. The short version: ggml's ARM kernels are selected when the
     * library is compiled, so a CPU without the dot-product extension crashes rather than running
     * slowly, and API level is not a proxy for having it.
     */
    override fun unsupportedReason(): String? = llamaCppUnsupportedReason()

    override fun loader(config: ModelConfig): ModelLoader = LlamaCppModelLoader(config)
}
