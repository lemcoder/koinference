package io.github.lemcoder.koinference.llamacpp

import io.github.lemcoder.koinference.ModelLoader

class LlamaCppModelLoader : ModelLoader {
    private val runtimes = mutableMapOf<String, LlamaCppRuntime>()

    override suspend fun load(modelPath: String): LlamaCppModelRuntime {
        require(modelPath.endsWith(".gguf")) {
            "llama.cpp loader expects a .gguf model path."
        }

        return runtimes.getOrPut(modelPath) { LlamaCppRuntime(modelPath) }
    }

    override suspend fun unload(modelPath: String) {
        runtimes.remove(modelPath)
    }
}

