package io.github.lemcoder.koinference

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
