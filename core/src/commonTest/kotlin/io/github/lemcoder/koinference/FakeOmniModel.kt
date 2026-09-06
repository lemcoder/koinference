package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.runtime.Connection
import io.github.lemcoder.koinference.runtime.Model

/** A model that answers in text and audio interleaved. */
internal class FakeOmniModel(private val config: ModelConfig) : Model {
    override suspend fun open(): Connection = FakeOmniConnection(config.parameters)
    override suspend fun close() {}
}
