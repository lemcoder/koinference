package io.github.lemcoder.koinference.cera.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A Cera binding that records instead of inferring.
 *
 * The whole point of [CeraBridge] being an interface: session reuse, what a parameter change
 * throws away, and what happens to an in-flight generation when the model is unloaded are all
 * decided in common code, and none of it needs a GGUF file to check.
 */
internal class FakeCeraBridge : CeraBridge {

    val models = mutableListOf<FakeCeraModel>()

    val model: FakeCeraModel get() = models.last()

    /** What generated replies look like, as a function of the prompt. */
    var reply: (String) -> String = { "reply to $it" }

    override fun openModel(options: CeraModelOptions): CeraModel =
        FakeCeraModel(options, reply).also { models += it }
}
