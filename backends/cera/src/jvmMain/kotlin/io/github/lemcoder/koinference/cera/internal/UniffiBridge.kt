package io.github.lemcoder.koinference.cera.internal

import io.github.lemcoder.koinference.runtime.Accelerator
import uniffi.cera_ffi.BackendPreference
import uniffi.cera_ffi.CeraEngine
import uniffi.cera_ffi.EngineConfig

/**
 * Cera through its UniFFI bindings.
 *
 * This file is duplicated verbatim in the other leg. That is deliberate — the JVM and Android
 * artifacts carry the same generated Kotlin API and differ only in which natives they package, so
 * there is nothing here to abstract over; see the rules at the top of `CLAUDE.md`.
 */
internal object UniffiBridge : CeraBridge {

    private fun engineConfig(options: CeraModelOptions): EngineConfig = EngineConfig(
        // 0 means the model's full declared context to Cera, which is what ModelConfig's 0 means
        // here too — the two conventions happen to agree, so there is nothing to translate.
        contextSize = options.contextTokens.coerceAtLeast(0).toULong(),
        backend = when (options.accelerator) {
            Accelerator.CPU -> BackendPreference.CPU
            // AUTO rather than GPU: Cera falls back to the CPU when no GPU backend was compiled
            // in, where GPU is an error. A benchmark that dies on a phone without a usable GPU has
            // measured nothing.
            Accelerator.GPU -> BackendPreference.AUTO
        },
        // No bundle repository: this backend loads a path, and auto-downloading a model from
        // Hugging Face behind a `load()` call is not something a benchmark should do.
        bundleRepo = null,
    )

    override fun openModel(options: CeraModelOptions): CeraModel = UniffiModel(
        engine = CeraEngine.fromPath(options.modelPath, engineConfig(options)),
        contextTokens = options.contextTokens,
    )
}
