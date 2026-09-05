package io.github.lemcoder.koinference.cera.internal

/**
 * The Cera binding, as something a test can replace.
 *
 * An interface rather than an `expect class` for the reason `docs/backends.md` gives: everything
 * the runtime decides — session reuse, what a settings change throws away, what happens to an
 * in-flight generation when the model is unloaded — would otherwise need a real GGUF to exercise.
 */
internal interface CeraBridge {

    fun openModel(options: CeraModelOptions): CeraModel
}

/** The binding this platform links. jvm and android both answer with the UniFFI one. */
internal expect fun platformBridge(): CeraBridge
