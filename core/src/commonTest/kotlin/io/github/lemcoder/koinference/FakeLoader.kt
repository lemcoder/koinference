package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.runtime.GeneratingRuntime

/** Caches per path the way the real loaders do, so "loaded twice" can be asserted. */
internal class FakeLoader(val config: ModelConfig) : ModelLoader {

    private val runtimes = mutableMapOf<String, FakeRuntime>()
    val unloaded = mutableListOf<String>()

    override suspend fun load(modelPath: String): GeneratingRuntime =
        runtimes.getOrPut(modelPath) { FakeRuntime(modelPath, config) }

    override suspend fun unload(modelPath: String) {
        if (runtimes.remove(modelPath) != null) unloaded += modelPath
    }

    override suspend fun unloadAll() {
        unloaded += runtimes.keys
        runtimes.clear()
    }
}
