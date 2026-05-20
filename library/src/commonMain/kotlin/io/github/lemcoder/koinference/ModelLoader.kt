package io.github.lemcoder.koinference

interface ModelLoader {
    suspend fun load(modelPath: String): ModelRuntime
    suspend fun unload(modelPath: String)
}
