package io.github.lemcoder.koinference.executorch.internal

import org.pytorch.executorch.extension.llm.LlmModule

/**
 * ExecuTorch through its published `LlmModule`.
 *
 * Nothing here is ours: the AAR carries the JNI library and the Kotlin class, so this backend has no
 * facade, no CMake and no C of its own — the same shape as `:backends:cera`, and for the same
 * reason.
 */
internal object LlmModuleBridge : ExecuTorchBridge {

    override fun openModel(options: ExecuTorchModelOptions): ExecuTorchModel = LlmModuleModel(
        module = LlmModule(
            options.modelPath,
            options.tokenizerPath,
            options.temperature.toFloat(),
        ),
    )
}
