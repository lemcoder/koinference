package io.github.lemcoder.koinference.benchmark.engine

import io.github.lemcoder.koinference.backend.Backend

/**
 * The backends this build links, in the order `engine=all` runs them.
 *
 * The only declaration in the harness that names a backend, and per platform because the set
 * genuinely differs: Cera's bindings are UniFFI over JNA, so it exists on Android and not on
 * macOS. Adding an engine to a benchmark is adding it to the legs that can load it.
 */
expect fun benchmarkBackends(): List<Backend>
