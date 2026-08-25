package io.github.lemcoder.koinference.benchmark.app.net

import io.github.lemcoder.koinference.benchmark.app.client.BackendConnection
import java.io.File
import kotlinx.coroutines.flow.Flow

/** A [ServedBackend] that reaches its model over the binder, in the engine's own process. */
class BinderServedBackend(
    private val connection: BackendConnection,
    override val engineId: String,
    override val modelPath: String,
) : ServedBackend {

    override val modelId: String = File(modelPath).nameWithoutExtension

    override fun stream(prompt: String, schema: String?): Flow<String> = connection.generate(prompt, schema)

    override suspend fun processMemory(): String = connection.processMemory()
}
