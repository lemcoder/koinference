package io.github.lemcoder.koinference.executorch.internal

/** A loaded `.pte` program and the tokenizer it was exported against. */
internal interface ExecuTorchModel {

    fun openSession(options: ExecuTorchSessionOptions): ExecuTorchSession

    fun close()
}
