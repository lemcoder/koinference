package io.github.lemcoder.koinference.benchmark.app.service

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.executorch.ExecuTorch

/** ExecuTorch, in the `:executorch` process. See the manifest. */
class ExecuTorchService : BackendService() {
    override val backend: Backend = ExecuTorch
}
