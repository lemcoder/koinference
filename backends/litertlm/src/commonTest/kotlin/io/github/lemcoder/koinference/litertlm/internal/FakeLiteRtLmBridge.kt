package io.github.lemcoder.koinference.litertlm.internal

import io.github.lemcoder.koinference.Accelerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A LiteRT-LM binding that records instead of inferring.
 *
 * This is the whole point of [LiteRtLmBridge] being an interface: everything the runtime and
 * the loader decide — when a conversation is reopened, whether the engine survives a settings
 * change, what happens to an in-flight generation when the model is unloaded — is decided in
 * common code, and none of it needs a 136 MB model to check.
 */
internal class FakeLiteRtLmBridge(
    /** Opening an engine on this backend throws, standing in for a device without a GPU. */
    private val unavailable: Accelerator? = null,
) : LiteRtLmBridge {

    val engines = mutableListOf<FakeEngine>()

    val engine: FakeEngine get() = engines.last()

    override fun openEngine(options: EngineOptions): LiteRtLmEngine {
        if (options.accelerator == unavailable) error("no ${options.accelerator} on this device")
        return FakeEngine(options).also { engines += it }
    }
}
