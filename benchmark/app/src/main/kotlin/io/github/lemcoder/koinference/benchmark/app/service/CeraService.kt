package io.github.lemcoder.koinference.benchmark.app.service

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.cera.Cera

/** Cera, in the `:cera` process. See the manifest. */
class CeraService : BackendService() {
    override val backend: Backend = Cera
}
