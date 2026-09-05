package io.github.lemcoder.koinference.cera.internal

import uniffi.cera_ffi.CeraEngine
import uniffi.cera_ffi.SessionConfig

/**
 * This file is duplicated verbatim in the other leg. That is deliberate: the JVM and Android
 * artifacts carry the same generated Kotlin API and differ only in which natives they package, so
 * there is nothing here to abstract over. See the rules at the top of `CLAUDE.md`.
 */
internal class UniffiModel(
    private val engine: CeraEngine,
    private val contextTokens: Int,
) : CeraModel {

    /** Content tokens: no BOS, no chat template — the same thing the other backends count. */
    override fun countTokens(text: String): Int = engine.encodeText(text).size

    override fun openSession(options: CeraSessionOptions): CeraSession = UniffiSession(
        engine = engine,
        session = engine.newSession(
            SessionConfig(
                maxSeqLen = options.contextTokens.takeIf { it > 0 }?.toUInt(),
                seed = options.seed?.toULong(),
            ),
        ),
        options = options,
    )

    override fun close() = engine.close()
}
