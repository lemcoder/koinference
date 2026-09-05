package io.github.lemcoder.koinference.executorch.internal

/**
 * What loading a `.pte` needs.
 *
 * [tokenizerPath] is separate because ExecuTorch keeps it separate: a `.pte` carries the program,
 * not the vocabulary, and `LlmModule` takes both paths. Where it comes from is
 * [TokenizerFile]'s problem, not this type's.
 */
internal data class ExecuTorchModelOptions(
    val modelPath: String,
    val tokenizerPath: String,
    val temperature: Double,
)
