package io.github.lemcoder.koinference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises access to a runtime's native handles and refuses to use them after unload.
 *
 * Every backend needs the same two guarantees and gets them wrong in the same two ways, so they
 * are written once here rather than per backend: one caller at a time, and no call at all once
 * the handles have been freed. See `docs/backends.md` for how this fits the rest of the seam.
 *
 * The check is inside the lock deliberately. Outside it, an unload could pass between the check
 * and the call, and freeing a session another coroutine is decoding into is a use-after-free
 * rather than an exception.
 *
 * @param describeTarget names the runtime in the failure message — a model path, normally.
 *        A function rather than a string because a backend may reload onto a different path.
 */
class RuntimeGuard(private val describeTarget: () -> String) {

    private val lock = Mutex()
    private var closed = false

    /** Runs [block] with exclusive access, or fails if this runtime has been unloaded. */
    suspend fun <T> whileOpen(block: suspend () -> T): T = lock.withLock {
        checkOpen()
        block()
    }

    /**
     * A cold flow that holds the lock for the whole collection.
     *
     * Streaming a reply is one long turn, not a series of independent calls: the session carries
     * decoder state, and a second generation starting half way through this one would interleave
     * into it. So the lock spans every emission rather than each one.
     */
    fun <T> streamWhileOpen(block: suspend FlowCollector<T>.() -> Unit): Flow<T> = flow {
        lock.withLock {
            checkOpen()
            block()
        }
    }

    /**
     * Marks this runtime unloaded and runs [release] once, under the lock.
     *
     * Idempotent, and it waits for an in-flight generation instead of freeing underneath one.
     */
    suspend fun close(release: suspend () -> Unit) {
        lock.withLock {
            if (closed) return@withLock
            closed = true
            release()
        }
    }

    /**
     * Marks this runtime unloaded without releasing anything.
     *
     * For the case where a reload has already freed the old handles and failed to produce new
     * ones: there is nothing left to release, and nothing left that may be called.
     */
    fun markClosed() {
        closed = true
    }

    private fun checkOpen() =
        check(!closed) { "This runtime has been unloaded: ${describeTarget()}" }
}
