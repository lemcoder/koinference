package io.github.lemcoder.koinference.executorch.internal

/**
 * The ExecuTorch binding, as something a test can replace.
 *
 * Same reason as the other backends: what the runtime decides — session reuse, what a settings
 * change throws away, use-after-unload — should be checkable without a `.pte` and a tokenizer.
 */
internal interface ExecuTorchBridge {

    fun openModel(options: ExecuTorchModelOptions): ExecuTorchModel
}

/** The binding this platform links. Android is the only leg ExecuTorch publishes for. */
internal expect fun platformBridge(): ExecuTorchBridge
