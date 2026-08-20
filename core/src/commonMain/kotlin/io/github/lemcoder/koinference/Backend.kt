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
