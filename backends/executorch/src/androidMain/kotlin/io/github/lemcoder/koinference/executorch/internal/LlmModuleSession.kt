package io.github.lemcoder.koinference.executorch.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmModule

/**
 * One generation over the module.
 *
 * `generate` blocks its thread and pushes pieces at [LlmCallback] as it decodes, which is the same
 * shape Cera's sink has: run it on an IO thread and let the channel carry the pieces out.
 * [trySendBlocking] rather than `trySend` so a fast model pushes back on the decoder instead of
 * dropping a piece and silently shortening the reply.
 */
internal class LlmModuleSession(
    private val module: LlmModule,
    private val options: ExecuTorchSessionOptions,
) : ExecuTorchSession {

    /** The last reply and what the engine said it cost, so [generatedTokens] can answer for it. */
    private var lastReply: String? = null
    private var lastTokens: Int? = null

    override fun reset() = module.resetContext()

    override fun generatedTokens(text: String): Int? =
        lastTokens.takeIf { lastReply == text }

    /** Drains the same loop [stream] pulls from, so there is one decode path and not two. */
    override suspend fun generate(prompt: String): String =
        stream(prompt).toList().joinToString("")

    override fun stream(prompt: String): Flow<String> = channelFlow {
        // ExecuTorch delivers one piece per generated token, so counting emissions is how the
        // budget is enforced — seqLen means something else entirely.
        var produced = 0
        val reply = StringBuilder()

        val callback = object : LlmCallback {
            override fun onResult(result: String) {
                if (options.maxOutputTokens in 1..produced) return
                produced++
                reply.append(result)
                trySendBlocking(result)
                if (options.maxOutputTokens in 1..produced) module.stop()
            }

            /**
             * The engine's own account of the generation, as JSON.
             *
             * Its *timings* are ignored — the harness owns the clock, and a second set of numbers
             * would only be comparable with itself. Its token count is kept, because this binding
             * exposes no tokenizer and this is the same tokenizer's count of the same reply.
             */
            override fun onStats(stats: String) {
                lastReply = reply.toString()
                lastTokens = ExecuTorchStats.generatedTokens(stats)
            }
        }

        withContext(Dispatchers.IO) {
            // Every turn starts from an empty context: the module carries its decoder position
            // across generations, and the next call would otherwise inherit it.
            module.resetContext()
            module.generate(prompt, sequenceLength(), callback, ECHO_PROMPT)
        }
    }

    override fun cancel() = module.stop()

    /**
     * Nothing to release: the module outlives the session and is closed by the model. Stopping an
     * in-flight generation is [cancel]'s job.
     */
    override fun close() = Unit

    /**
     * ExecuTorch caps a generation by *sequence* length: prompt and reply together, against the
     * model's own context window.
     *
     * So a token budget must not be passed here, which is the mistake that made the first device run
     * fail: `maxNewTokens=32` became `seqLen=32`, and ExecuTorch resolved the budget as
     * `seqLen - pos_ - promptTokens` and refused with "Max new tokens 0". The budget is enforced in
     * [stream] instead, by stopping the module once it has produced enough.
     *
     * `LlmGenerationConfig`, which does have a real `maxNewTokens`, cannot be built from outside the
     * AAR: its `Builder` constructor is Kotlin-`internal`, so it is public only to `javap`.
     */
    private fun sequenceLength(): Int =
        options.contextTokens.takeIf { it > 0 } ?: DEFAULT_SEQUENCE_LENGTH

    private companion object {
        /**
         * The prompt is not part of the reply.
         *
         * ExecuTorch echoes it by default — text completion rather than chat — which would put the
         * question in front of every answer and into every character count the harness takes.
         */
        const val ECHO_PROMPT = false

        /**
         * Prompt plus reply, when the caller named no context size.
         *
         * Capped by the model's own `max_context_len` anyway — stories110M's is 128 — so this is a
         * ceiling to leave room under, not a promise of room.
         */
        const val DEFAULT_SEQUENCE_LENGTH = 512
    }
}
