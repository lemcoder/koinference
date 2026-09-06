package io.github.lemcoder.koinference.runtime.internal

import io.github.lemcoder.koinference.ModelId
import io.github.lemcoder.koinference.prompt.PromptPart
import io.github.lemcoder.koinference.runtime.Connection
import io.github.lemcoder.koinference.runtime.GeneratingRuntime
import io.github.lemcoder.koinference.runtime.GenerationConstraint
import io.github.lemcoder.koinference.runtime.KoinferenceException
import io.github.lemcoder.koinference.runtime.ResponsePart
import kotlinx.coroutines.flow.collect

/**
 * The default [Connection], adapting a [GeneratingRuntime] to the callback seam.
 *
 * Internal on purpose: the `Flow` the runtime still streams is collected *here*, behind the seam,
 * and delivered to the public callback — the published surface never sees a `Flow`. When the backend
 * runtimes migrate to a callback natively, only this file changes, not the public [Connection].
 *
 * No `markClosed`: a connection has exactly one dead state (`closed`), reached by [close] (the
 * caller tore it down) or [die] (something tore it down underneath the caller). There is nothing to
 * "mark closed without releasing", because releasing the weights is the *model's* job now, not the
 * connection's — which is the whole point of splitting them.
 */
internal class RuntimeConnection(
    override val modelId: ModelId,
    private val runtime: GeneratingRuntime,
    private val onDeath: (KoinferenceException) -> Unit,
    private val onClose: (RuntimeConnection) -> Unit,
) : Connection {

    private var closed = false

    override suspend fun generate(
        prompt: List<PromptPart>,
        constraint: GenerationConstraint?,
        onPart: (ResponsePart) -> Unit,
    ) {
        if (closed) throw KoinferenceException.ConnectionClosed(modelId)
        try {
            // Flow strictly behind the seam; the caller only ever sees onPart.
            runtime.streamResponse(prompt, constraint).collect { part -> onPart(part) }
        } catch (failure: KoinferenceException) {
            throw failure
        } catch (failure: Throwable) {
            throw KoinferenceException.GenerationFailed(
                "Generation on $modelId failed: ${failure.message}", failure,
            )
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        onClose(this)
    }

    /**
     * Out-of-band teardown: the model was unloaded, or the engine died, under this connection.
     *
     * Marks it closed and notifies via `onDeath`. Every later call then throws
     * [KoinferenceException.ConnectionClosed] rather than touching a freed handle.
     */
    fun die(reason: KoinferenceException) {
        if (closed) return
        closed = true
        onDeath(reason)
    }
}
