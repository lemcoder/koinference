package io.github.lemcoder.koinference.executorch.internal

internal class FakeExecuTorchModel(
    val options: ExecuTorchModelOptions,
    private val reply: (String) -> String,
) : ExecuTorchModel {

    val sessions = mutableListOf<FakeExecuTorchSession>()

    val session: FakeExecuTorchSession get() = sessions.last()

    var closed = false
        private set

    override fun openSession(options: ExecuTorchSessionOptions): ExecuTorchSession =
        FakeExecuTorchSession(options, reply).also { sessions += it }

    override fun close() {
        closed = true
    }
}
