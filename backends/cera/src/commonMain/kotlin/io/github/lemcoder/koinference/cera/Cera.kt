package io.github.lemcoder.koinference.cera

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.backend.SamplingKnob
import io.github.lemcoder.koinference.runtime.Modality

/**
 * Cera, as something a [io.github.lemcoder.koinference.Koinference] can hold.
 *
 * **It reads GGUF, and so does llama.cpp.** `Koinference.backendFor` returns the first registered
 * backend that handles a path, so registration order decides which engine a `.gguf` goes to;
 * `backendById("cera")` is how a caller says which one it meant regardless of order. See
 * `docs/backends.md`.
 */
object Cera : Backend {

    override val id: String = "cera"

    /** Text out. Cera also decodes audio, which this backend does not wire up. */
    override val modalities: Set<Modality> = setOf(Modality.TEXT)

    override fun handles(modelPath: String): Boolean = modelPath.endsWith(".gguf")

    /**
     * Everything except the seed's placement is per generation; the seed is per session, which is
     * why this backend can claim it at all — asserted in `CeraBackendTest`.
     */
    override val honours: Set<SamplingKnob> = setOf(
        SamplingKnob.TEMPERATURE,
        SamplingKnob.TOP_K,
        SamplingKnob.TOP_P,
        SamplingKnob.MIN_P,
        SamplingKnob.SEED,
    )

    override fun loader(config: ModelConfig): ModelLoader = CeraModelLoader(config)
}
