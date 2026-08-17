package io.github.lemcoder.koinference.litertlm.internal

import io.github.lemcoder.koinference.GenerationTelemetry
import io.github.lemcoder.koinference.InferenceBackend

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
    private val unavailable: InferenceBackend? = null,
) : LiteRtLmBridge {

    val engines = mutableListOf<FakeEngine>()

    val engine: FakeEngine get() = engines.last()

    override fun openEngine(options: EngineOptions): LiteRtLmEngine {
        if (options.backend == unavailable) error("no ${options.backend} on this device")
        return FakeEngine(options).also { engines += it }
    }
}

internal class FakeEngine(val options: EngineOptions) : LiteRtLmEngine {

    val conversations = mutableListOf<FakeConversation>()
    var closed = false
        private set

    val conversation: FakeConversation get() = conversations.last()

    override fun openConversation(options: ConversationOptions): LiteRtLmConversation =
        FakeConversation(options).also { conversations += it }

    override fun close() {
        closed = true
    }
}

internal class FakeConversation(val options: ConversationOptions) : LiteRtLmConversation {

    val turns = mutableListOf<Turn>()
    var closed = false
        private set

    /** Runs inside [generate], for tests that need to hold a generation open. */
    var whileGenerating: (() -> Unit)? = null

    override fun generate(prompt: String, jsonSchema: String?): GeneratedReply {
        turns += Turn(prompt, jsonSchema)
        whileGenerating?.invoke()
        return GeneratedReply("reply to $prompt", telemetry)
    }

    /** What [generate] reports. Null by default, matching a binding that cannot measure. */
    var telemetry: GenerationTelemetry? = null

    override fun close() {
        closed = true
    }

    data class Turn(val prompt: String, val jsonSchema: String?)
}
