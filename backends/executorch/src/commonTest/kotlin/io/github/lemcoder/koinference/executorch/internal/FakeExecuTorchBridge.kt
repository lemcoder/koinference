package io.github.lemcoder.koinference.executorch.internal

/** An ExecuTorch binding that records instead of inferring. */
internal class FakeExecuTorchBridge : ExecuTorchBridge {

    val models = mutableListOf<FakeExecuTorchModel>()

    val model: FakeExecuTorchModel get() = models.last()

    var reply: (String) -> String = { "reply to $it" }

    override fun openModel(options: ExecuTorchModelOptions): ExecuTorchModel =
        FakeExecuTorchModel(options, reply).also { models += it }
}
