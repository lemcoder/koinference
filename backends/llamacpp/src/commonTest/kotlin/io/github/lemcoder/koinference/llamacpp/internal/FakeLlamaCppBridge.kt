package io.github.lemcoder.koinference.llamacpp.internal

import io.github.lemcoder.koinference.Accelerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A llama.cpp binding that records instead of inferring.
 *
 * The counterpart of `FakeLiteRtLmBridge`, and the whole point of [LlamaCppBridge] being an
 * interface: everything the runtime and the loader decide — when a session is rebuilt, whether
 * the weights survive a settings change, what happens to an in-flight generation when the model
 * is unloaded — is decided in common code, and none of it needs a GGUF file to check.
 */
internal class FakeLlamaCppBridge(
    /** Loading a model on this backend throws, standing in for a device without a GPU. */
    private val unavailable: Accelerator? = null,
) : LlamaCppBridge {

    val models = mutableListOf<FakeModel>()

    val model: FakeModel get() = models.last()

    /** Schemas this fake refuses, standing in for one llama.cpp cannot convert. */
    var unconvertibleSchemas: Set<String> = emptySet()

    /**
     * What generated replies look like. Set on the bridge rather than on a session because a
     * session does not exist until the first turn, and a test about an empty reply has to say so
     * before then.
     */
    var reply: (String) -> String = { "reply to $it" }

    override fun openModel(options: ModelOptions): LlamaCppModel {
        if (options.accelerator == unavailable) error("no ${options.accelerator} on this device")
        return FakeModel(options, reply).also { models += it }
    }

    override fun jsonSchemaToGrammar(schema: String): String {
        require(schema !in unconvertibleSchemas) { "Not a convertible JSON schema: $schema" }
        return "grammar for $schema"
    }
}

internal class FakeModel(
    val options: ModelOptions,
    private val reply: (String) -> String,
) : LlamaCppModel {

    val sessions = mutableListOf<FakeSession>()
    var closed = false
        private set

    val session: FakeSession get() = sessions.last()

    override fun openSession(options: SessionOptions): LlamaCppSession =
        FakeSession(options, reply).also { sessions += it }

    override fun close() {
        closed = true
    }
}

internal class FakeSession(
    val options: SessionOptions,
    private val reply: (String) -> String,
) : LlamaCppSession {

    val turns = mutableListOf<Turn>()
    var closed = false
        private set

    /** Runs inside [generate] and [stream], for tests that need to hold a generation open. */
    var whileGenerating: (() -> Unit)? = null

    /**
     * Chunks [stream] emits.
     *
     * The default cuts the canned reply into fixed-size pieces rather than words: they have to
     * concatenate back to exactly what [generate] returns, which is the property the streaming
     * contract promises, and splitting on spaces loses them.
     */
    var chunks: List<String>? = null

    /** Set when [stream] ran its `finally`, so an abandoned collection is observable. */
    var streamEnded = false
        private set

    override fun generate(systemPrompt: String?, prompt: String, grammar: String?): String {
        turns += Turn(systemPrompt, prompt, grammar)
        whileGenerating?.invoke()
        return reply(prompt)
    }

    override fun stream(systemPrompt: String?, prompt: String, grammar: String?): Flow<String> =
        flow {
            turns += Turn(systemPrompt, prompt, grammar)
            whileGenerating?.invoke()
            try {
                (chunks ?: reply(prompt).chunked(3)).forEach { emit(it) }
            } finally {
                streamEnded = true
            }
        }

    /** Whitespace words, which is enough for a fake: tests assert plumbing, not tokenization. */
    override fun tokenCount(text: String): Int = text.split(" ").count { it.isNotBlank() }

    override fun close() {
        closed = true
    }

    data class Turn(val systemPrompt: String?, val prompt: String, val grammar: String?)
}
