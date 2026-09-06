package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.runtime.Connection
import io.github.lemcoder.koinference.runtime.Model
import io.github.lemcoder.koinference.runtime.media.ResponsePart

/** A text-only model: every part its connections emit is [ResponsePart.Text]. */
internal class FakeModel(val modelPath: String, private val config: ModelConfig) : Model {

    override suspend fun open(): Connection =
        FakeGeneratingConnection(modelPath, config.parameters) {
            // Multiple parts so streaming has something to stream; they concatenate to one reply.
            listOf(ResponsePart.Text("reply "), ResponsePart.Text("from "), ResponsePart.Text(modelPath))
        }

    override suspend fun close() {}
}
