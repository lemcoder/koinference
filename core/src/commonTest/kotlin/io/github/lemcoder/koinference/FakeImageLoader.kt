package io.github.lemcoder.koinference

import io.github.lemcoder.koinference.backend.ModelConfig
import io.github.lemcoder.koinference.backend.ModelLoader
import io.github.lemcoder.koinference.runtime.ModelRuntime

internal class FakeImageLoader(val config: ModelConfig) : ModelLoader {

    private val runtimes = mutableMapOf<String, FakeImageRuntime>()

    override suspend fun load(modelPath: String): ModelRuntime =
        runtimes.getOrPut(modelPath) { FakeImageRuntime(config) }

    override suspend fun unload(modelPath: String) {
        runtimes.remove(modelPath)
    }

    override suspend fun unloadAll() = runtimes.clear()
}
