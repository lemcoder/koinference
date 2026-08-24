package io.github.lemcoder.koinference.litertlm.internal

import io.github.lemcoder.koinference.runtime.GenerationParameters
import io.github.lemcoder.koinference.runtime.Accelerator
import kotlinx.coroutines.flow.Flow

/** One conversation over an engine, carrying its own prefilled state. */
internal interface LiteRtLmConversation {
    /**
     * Send one message and wait for the reply (blocking).
     *
     * @param jsonSchema JSON schema for constrained decoding, or null for unconstrained.
     */
    fun generate(prompt: String, jsonSchema: String?): String

    /**
     * Stream the reply, one chunk per emission.
     *
     * The Apple leg pulls from the facade, which buffers what the runtime pushes from its own
     * thread; Android collects the SDK's own flow. Both hand back chunks and nothing else —
     * whoever is timing decides when each one arrived.
     */
    fun stream(prompt: String, jsonSchema: String?): Flow<String>

    fun close()
}
