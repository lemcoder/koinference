package io.github.lemcoder.koinference

/**
 * The backends an application was built with.
 *
 * Assembled by the consumer — `BackendRegistry(LlamaCpp, LiteRtLm)` — because that is the only
 * place that knows which modules were linked. Resolution failures name what *is* available, since
 * the usual cause is a typo or a module that was not depended on.
 */
class BackendRegistry(val backends: List<Backend>) {

    constructor(vararg backends: Backend) : this(backends.toList())

    init {
        val duplicates = backends.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate backend ids: $duplicates" }
    }

    val ids: List<String> get() = backends.map { it.id }

    fun byId(id: String): Backend? = backends.firstOrNull { it.id == id }

    /** The first backend that reads this container, or null if none was registered for it. */
    fun forModel(modelPath: String): Backend? = backends.firstOrNull { it.handles(modelPath) }

    fun requireById(id: String): Backend =
        byId(id) ?: error("Unknown backend '$id'. Registered: $ids")

    fun requireForModel(modelPath: String): Backend =
        forModel(modelPath) ?: error("No registered backend reads $modelPath. Registered: $ids")
}
