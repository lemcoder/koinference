package io.github.lemcoder.koinference.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises access to a connection's native handles and refuses them after it is closed.
 *
 * Every backend needs the same two guarantees over a decode context and got them wrong the same
 * ways, so they are written once: one caller at a time, and no call once the handles are freed.
 *
 * The check is inside the lock deliberately — outside it, a close could pass between the check and
 * the call, and freeing a session another coroutine is decoding into is a use-after-free rather than
 * an exception.
 *
 * There is no `markClosed`. It existed because the old runtime freed a model in place to retune it
 * and could be left holding nothing; a connection never frees the weights — that is the model's job
 * — so its only dead state is [close].
 *
 * @param describeTarget names the connection in the failure message — a model id, normally.
 */
class ConnectionGuard(private val describeTarget: () -> String) {

    private val lock = Mutex()
    private var closed = false

    /** Runs [block] with exclusive access, or fails if this connection has been closed. */
    suspend fun <T> whileOpen(block: suspend () -> T): T = lock.withLock {
        if (closed) throw KoinferenceException.ConnectionClosed(describeTarget())
        block()
    }

    /**
     * Marks the connection closed and runs [release] once, under the lock.
     *
     * Idempotent, and it waits for an in-flight call instead of freeing underneath one.
     */
    suspend fun close(release: suspend () -> Unit) {
        lock.withLock {
            if (closed) return@withLock
            closed = true
            release()
        }
    }
}
