package io.github.lemcoder.koinference.benchmark.app.service

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.llamacpp.LlamaCpp

/** llama.cpp, in the `:llamacpp` process. See the manifest. */
class LlamaCppService : BackendService() {
    override val backend: Backend = LlamaCpp
}
