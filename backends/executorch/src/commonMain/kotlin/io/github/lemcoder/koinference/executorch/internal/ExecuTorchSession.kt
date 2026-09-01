package io.github.lemcoder.koinference.executorch.internal

import kotlinx.coroutines.flow.Flow

/**
 * One generation handle over an [ExecuTorchModel].
 *
 * A thin view rather than an engine concept: `LlmModule` is both the loaded program and the thing
 * that decodes, so ExecuTorch has no session to open. The tier is kept because the seam is the same
 * in every backend and a reader should not have to learn a second shape — see `docs/backends.md`.
 */
internal interface ExecuTorchSession {

    /**
     * Drops the decoder position, so the next turn starts from an empty context.
     *
     * `LlmModule` carries `pos_` across generations. Without this the second call to the same
     * runtime fails outright rather than merely slowing down: ExecuTorch resolves the new-token
     * budget as `seqLen - pos_ - promptTokens`, and reports "Max new tokens 0 is less than or equal
     * to 0" once the position has eaten the window.
     */
    fun reset()

    suspend fun generate(prompt: String): String

    fun stream(prompt: String): Flow<String>

    /** Ends an in-flight generation. The module survives; the next call starts a fresh one. */
    fun cancel()

    fun close()
}
