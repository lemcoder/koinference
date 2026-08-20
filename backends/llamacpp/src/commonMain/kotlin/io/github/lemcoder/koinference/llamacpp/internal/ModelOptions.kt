package io.github.lemcoder.koinference.llamacpp.internal

import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.Accelerator
import kotlinx.coroutines.flow.Flow

internal data class ModelOptions(
    val modelPath: String,
    /**
     * Where the model runs.
     *
     * On the model rather than the session because llama.cpp decides GPU offload when the
     * weights are loaded (`llama_model_params.n_gpu_layers`); nothing short of a reload moves a
     * loaded model. A build with no GPU backend compiled in ignores the request rather than
     * failing, so GPU on such a target is CPU inference and not an error.
     */
    val accelerator: Accelerator = Accelerator.CPU,
)
