package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.runtime.Model

internal class FakeOmniLoader(val config: ModelConfig) : ModelLoader {

    private val models = mutableMapOf<String, FakeOmniModel>()

    override suspend fun load(modelPath: String): Model =
        models.getOrPut(modelPath) { FakeOmniModel(config) }

    override suspend fun unload(modelPath: String) { models.remove(modelPath) }

    override suspend fun unloadAll() = models.clear()
}
