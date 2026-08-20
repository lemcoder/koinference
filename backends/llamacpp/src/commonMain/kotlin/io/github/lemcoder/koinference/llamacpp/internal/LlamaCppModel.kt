package io.github.lemcoder.koinference.llamacpp.internal

import io.github.lemcoder.koinference.GenerationParameters
import io.github.lemcoder.koinference.Accelerator
import kotlinx.coroutines.flow.Flow

/** A model with its weights loaded. */
internal interface LlamaCppModel {

    fun openSession(options: SessionOptions): LlamaCppSession

    /** Releases the weights. Calling anything on the model afterwards is undefined. */
    fun close()
}
