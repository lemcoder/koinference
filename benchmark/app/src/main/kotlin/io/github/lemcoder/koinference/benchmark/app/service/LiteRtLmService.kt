package io.github.lemcoder.koinference.benchmark.app.service

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.litertlm.LiteRtLm

/** LiteRT-LM, in the `:litertlm` process. See the manifest. */
class LiteRtLmService : BackendService() {
    override val backend: Backend = LiteRtLm
}
