package io.github.lemcoder.koinference.executorch.internal

import org.pytorch.executorch.extension.llm.LlmModule

/**
 * The loaded program.
 *
 * `LlmModule` is both the program and the decoder, so a session is a view over it rather than a
 * handle of its own — see [ExecuTorchSession].
 */
internal class LlmModuleModel(private val module: LlmModule) : ExecuTorchModel {

    override fun openSession(options: ExecuTorchSessionOptions): ExecuTorchSession =
        LlmModuleSession(module, options)

    override fun close() = module.close()
}
