package io.github.lemcoder.koinference.litertlm.internal

import io.github.lemcoder.koinference.InferenceBackend
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

    /** Whitespace words, which is enough for a fake: tests assert plumbing, not tokenization. */
    override fun tokenCount(text: String): Int = text.split(" ").count { it.isNotBlank() }

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

    override fun generate(prompt: String, jsonSchema: String?): String {
        turns += Turn(prompt, jsonSchema)
        whileGenerating?.invoke()
        return "reply to $prompt"
    }

    /** Chunks [stream] emits; the default splits the canned reply so collectors see several. */
    var chunks: List<String>? = null

    override fun stream(prompt: String, jsonSchema: String?): Flow<String> = flow {
        turns += Turn(prompt, jsonSchema)
        whileGenerating?.invoke()
        (chunks ?: "reply to $prompt".split(" ").map { "$it " }).forEach { emit(it) }
    }

    override fun close() {
        closed = true
    }

    data class Turn(val prompt: String, val jsonSchema: String?)
}
