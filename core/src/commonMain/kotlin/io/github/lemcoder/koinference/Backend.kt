package io.github.lemcoder.koinference

/**
 * An inference engine this library can load models with.
 *
 * The point of the type is that callers do not name a backend's classes. Picking one, configuring
 * it and loading a model are the same three calls whichever engine answers, so swapping engines is
 * changing one value rather than editing every call site — and adding an engine touches no file in
 * `:core`.
 *
 * Implementations are objects in their backend module, so a consumer depends on the modules it
 * wants and assembles them into a [BackendRegistry]. `:core` deliberately does not enumerate them;
 * it cannot, since every backend depends on it rather than the other way round, and an enum here
 * would mean a new engine could not be added without editing this module and every exhaustive
 * `when` over it.
 */
interface Backend {

    /**
     * Stable identifier, e.g. `llama.cpp`.
     *
     * A string rather than an enum constant because the set is open. It is also what crosses
     * process boundaries — an instrumentation argument, a field in a results file — so it must
     * stay stable once published.
     */
    val id: String

    /**
     * Whether this backend can load [modelPath], judged by the container the path names.
     *
     * Containers are what actually distinguish the engines: llama.cpp reads GGUF, LiteRT-LM reads
     * `.litertlm` and `.task` and rejects a raw `.tflite`. A backend claiming a path it cannot
     * read is a bug in the backend, not a question for the caller.
     */
    fun handles(modelPath: String): Boolean

    /**
     * Which of [GenerationParameters]' knobs this backend actually applies.
     *
     * Declared rather than documented in prose, because "the engine ignored that" is otherwise
     * invisible: a caller sets a seed, the run is not reproducible, and nothing said so. A
     * backend never substitutes one knob for another — min-p is not top-p — so a knob absent from
     * this set is simply not applied.
     */
    val honours: Set<SamplingKnob>

    /** A loader configured with [config]. Knobs this engine has no equivalent for are ignored. */
    fun loader(config: ModelConfig): ModelLoader
}

/** The individually supported sampling knobs of [GenerationParameters]. */
enum class SamplingKnob {
    TOP_K,
    TOP_P,
    MIN_P,
    TEMPERATURE,
    SEED,
}

/**
 * What every backend is configured with.
 *
 * One vocabulary for knobs the engines spell differently — llama.cpp's `nCtx`/`nPredict` are
 * LiteRT-LM's `maxTokens`/`maxOutputTokens`, and a caller comparing the two had to know both. A
 * field an engine has no equivalent for is ignored rather than approximated, and the backend
 * documents which ones those are.
 *
 * @param systemPrompt     Applied to every generation. Some models' chat templates reject one.
 * @param settings         Where the model runs.
 * @param parameters       Sampling. Each backend reads the subset it supports and ignores the rest.
 * @param contextTokens    Context budget in tokens; 0 leaves the model's own.
 * @param maxOutputTokens  Cap on tokens per reply; 0 leaves the engine's own. Both engines fix
 *                         this when the model is loaded rather than per request, which is why it
 *                         is here and not on a generate call.
 * @param threads          CPU threads; 0 lets the engine pick.
 * @param cacheDir         Writable directory an engine may use to speed up later loads of the same
 *                         model. Must be a directory the process can write to, not merely read.
 */
data class ModelConfig(
    val systemPrompt: String? = null,
    val settings: RuntimeSettings = RuntimeSettings(),
    val parameters: GenerationParameters = GenerationParameters(),
    val contextTokens: Int = 0,
    val maxOutputTokens: Int = 0,
    val threads: Int = 0,
    val cacheDir: String? = null,
)

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
