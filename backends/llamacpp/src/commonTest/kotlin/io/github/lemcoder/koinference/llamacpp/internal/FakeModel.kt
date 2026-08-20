package io.github.lemcoder.koinference.llamacpp.internal

import io.github.lemcoder.koinference.Accelerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
