package io.github.lemcoder.koinference.benchmark.engine

import io.github.lemcoder.koinference.backend.Backend
import io.github.lemcoder.koinference.cera.Cera
import io.github.lemcoder.koinference.litertlm.LiteRtLm
import io.github.lemcoder.koinference.llamacpp.LlamaCpp

/**
 * All three. llama.cpp comes before Cera, so a `.gguf` asked for by path goes to llama.cpp unless a
 * caller names the engine — see `docs/backends.md` on the two GGUF readers.
 */
actual fun benchmarkBackends(): List<Backend> = listOf(LlamaCpp, LiteRtLm, Cera)
