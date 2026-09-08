package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.runtime.Model

/** Caches per path the way the real loaders do, so "loaded twice" can be asserted. */
internal class FakeLoader(val config: ModelConfig) : ModelLoader {

    private val models = mutableMapOf<String, FakeModel>()
    val unloaded = mutableListOf<String>()

    override suspend fun load(modelPath: String): Model =
        models.getOrPut(modelPath) { FakeModel(modelPath, config) }

    override suspend fun unload(modelPath: String) {
        if (models.remove(modelPath) != null) unloaded += modelPath
    }

    override suspend fun unloadAll() {
        unloaded += models.keys
        models.clear()
    }
}
