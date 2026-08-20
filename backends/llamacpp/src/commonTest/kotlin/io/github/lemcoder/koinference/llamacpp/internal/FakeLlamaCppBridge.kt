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
