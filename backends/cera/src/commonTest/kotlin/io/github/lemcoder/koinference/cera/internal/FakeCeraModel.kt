package io.github.lemcoder.koinference.cera.internal

internal class FakeCeraModel(
    val options: CeraModelOptions,
    private val reply: (String) -> String,
) : CeraModel {

    val sessions = mutableListOf<FakeCeraSession>()

    val session: FakeCeraSession get() = sessions.last()

    var closed = false
        private set

    /** Whitespace words; the point is that the runtime asks the model, not how well this counts. */
    override fun countTokens(text: String): Int = text.split(" ").count { it.isNotBlank() }

    override fun openSession(options: CeraSessionOptions): CeraSession =
        FakeCeraSession(options, reply).also { sessions += it }

    override fun close() {
        closed = true
    }
}
