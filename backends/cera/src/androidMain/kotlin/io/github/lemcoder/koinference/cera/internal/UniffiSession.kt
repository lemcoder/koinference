package io.github.lemcoder.koinference.cera.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import uniffi.cera_ffi.CeraEngine
import uniffi.cera_ffi.ChatMessage
import uniffi.cera_ffi.FinishReason
import uniffi.cera_ffi.GenerateOpts
import uniffi.cera_ffi.ModalitySink
import uniffi.cera_ffi.Session

/**
 * This file is duplicated verbatim in the other leg. That is deliberate: the JVM and Android
 * artifacts carry the same generated Kotlin API and differ only in which natives they package, so
 * there is nothing here to abstract over. See the rules at the top of `CLAUDE.md`.
 */
internal class UniffiSession(
    private val engine: CeraEngine,
    private val session: Session,
    private val options: CeraSessionOptions,
) : CeraSession {

    override fun reset() = session.reset()

    override suspend fun generate(prompt: String, grammar: String?): String =
        withContext(Dispatchers.IO) {
            session.appendText(templated(prompt))
            // Cera answers with token ids and a summary, never text: decoding them is this side's
            // job, and it is the same call the streaming path makes per batch.
            engine.decodeTokens(session.generate(opts(grammar)).tokens)
        }

    /**
     * Cera pushes token batches at a sink from its own worker; this pulls them.
     *
     * The blocking `generateStreaming` rather than the async one: the async variant's sink never
     * fired a single batch here, while this one delivers them as they are decoded. It is called on
     * an IO thread and [trySendBlocking] pushes back onto Cera's worker when the collector is slow,
     * so a fast model cannot overflow the channel and silently drop a chunk.
     */
    override fun stream(prompt: String, grammar: String?): Flow<String> = channelFlow {
        val sink = object : ModalitySink {
            override fun onTextTokens(tokens: List<UInt>) {
                if (tokens.isNotEmpty()) trySendBlocking(engine.decodeTokens(tokens))
            }

            override fun onAudioFrames(samples: List<Float>, sampleRate: UInt) = Unit

            override fun onDone(reason: FinishReason) = Unit
        }

        withContext(Dispatchers.IO) {
            session.appendText(templated(prompt))
            session.generateStreaming(opts(grammar), sink)
        }
    }

    override fun close() = session.close()

    /**
     * Wraps the prompt in the model's own chat template.
     *
     * Not optional: an instruct model handed raw text answers with the same token repeated to the
     * budget — LFM2.5 emits `?` twenty-four times. Cera exposes the template rather than applying
     * it, so this is where the turn is built, the same place the other backends build theirs.
     */
    private fun templated(prompt: String): String {
        if (!engine.hasChatTemplate()) return prompt

        val messages = buildList {
            options.systemPrompt?.let { add(ChatMessage("system", it)) }
            add(ChatMessage("user", prompt))
        }
        return engine.applyChatTemplate(messages, true)
    }

    private fun opts(grammar: String?): GenerateOpts = GenerateOpts(
        maxTokens = options.maxOutputTokens.takeIf { it > 0 }?.toUInt() ?: 256u,
        temperature = options.temperature?.toFloat() ?: 0.7f,
        topK = options.topK?.toUInt() ?: 40u,
        topP = options.topP?.toFloat() ?: 0.9f,
        minP = options.minP?.toFloat() ?: 0.05f,
        grammar = grammar,
    )
}
