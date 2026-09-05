package io.github.lemcoder.koinference.benchmark.engine

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.litertlm.LiteRtLm
import io.github.lemcoder.koinference.llamacpp.LlamaCpp

/** No Cera here: its Kotlin bindings are UniFFI over JNA, and this leg is Kotlin/Native. */
actual fun benchmarkBackends(): List<Backend> = listOf(LlamaCpp, LiteRtLm)
