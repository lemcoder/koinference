package io.github.lemcoder.koinference.benchmark

/**
 * Turns the flat key/value arguments a launcher supplies into a [BenchmarkConfig].
 *
 * Common code rather than part of the Android instrumentation, because none of it is Android:
 * it is defaulting, splitting and range policy, and every one of those decisions changes what a
 * result means. Living in an instrumented test made it reachable only from an emulator, so the
 * rules below were unverified exactly where a wrong one is invisible — a workload silently
 * dropped, a token budget quietly halved.
 *
 * Nothing here guesses at model identity. `modelId`, `quantization` and `modelSha256` are
 * recorded as given, because a wrong label makes two incomparable runs look comparable and no
 * amount of inspecting the file fixes that.
 */
object BenchmarkArguments {

    const val DEFAULT_MAX_NEW_TOKENS = 128
    const val DEFAULT_WARMUP = 1
    const val DEFAULT_ITERATIONS = 5
    const val DEFAULT_SEED = 42

    /** Prompt sets that are named rather than listed. */
    const val PROMPT_SET_ALL = "all"
    const val PROMPT_SET_DEFAULT = "default"

    private val DEFAULT_PROMPT_IDS =
        listOf("short_generation_v1", "long_generation_v1", "long_context_v1")

    /**
     * Token budgets that override a smaller requested one, by prompt family.
     *
     * A long-generation prompt capped at the default 128 tokens measures something other than
     * long generation, so the budget rises to fit the workload rather than the workload being
     * quietly truncated to fit the budget.
     */
    private val MINIMUM_BUDGETS = listOf(
        "long_generation" to 512,
        "reasoning" to 384,
    )

    /**
     * @param arguments the launcher's key/value pairs; a missing key takes the default.
     * @param runIdFallback used when no `runId` was given — a caller-supplied value, because
     *        common code has no clock.
     * @param corpusPromptIds every id in the corpus, for the `all` prompt set.
     * @throws IllegalArgumentException if a required argument is missing.
     */
    fun toConfig(
        arguments: Map<String, String>,
        corpusPromptIds: List<String>,
        runIdFallback: String,
    ): BenchmarkConfig {
        val modelPath = requireNotNull(arguments["model"]) {
            "-e model <path> is required: the harness never guesses where the weights are"
        }
        val maxNewTokens = arguments["maxNewTokens"]?.toIntOrNull() ?: DEFAULT_MAX_NEW_TOKENS

        return BenchmarkConfig(
            benchmarkRunId = arguments["runId"] ?: runIdFallback,
            engineIds = engineIds(arguments["engine"]),
            model = BenchmarkModelConfig(
                modelId = arguments["modelId"] ?: modelIdOf(modelPath),
                modelVersion = arguments["modelVersion"] ?: "unknown",
                modelPath = modelPath,
                quantization = arguments["quantization"] ?: quantizationOf(modelPath),
                sha256 = arguments["modelSha256"],
                maxContextTokens = arguments["maxContextTokens"]?.toIntOrNull() ?: 0,
                threads = arguments["threads"]?.toIntOrNull() ?: 0,
                useGpu = arguments["gpu"]?.toBooleanStrictOrNull() ?: false,
            ),
            workloads = workloadsFor(
                set = arguments["promptSet"] ?: PROMPT_SET_DEFAULT,
                corpusPromptIds = corpusPromptIds,
                maxNewTokens = maxNewTokens,
            ),
            sampling = SamplingConfig(
                temperature = arguments["temperature"]?.toDoubleOrNull() ?: 0.0,
                topK = arguments["topK"]?.toIntOrNull(),
                topP = arguments["topP"]?.toDoubleOrNull(),
                seed = arguments["seed"]?.toIntOrNull() ?: DEFAULT_SEED,
            ),
            warmupIterations = arguments["warmup"]?.toIntOrNull() ?: DEFAULT_WARMUP,
            measurementIterations = arguments["iterations"]?.toIntOrNull() ?: DEFAULT_ITERATIONS,
            sustainedDurationSeconds = arguments["sustainedDurationSeconds"]?.toIntOrNull() ?: 0,
            ftlModelId = arguments["ftlModelId"],
            ftlVersion = arguments["ftlVersion"],
        )
    }

    /** Comma-separated, or `all`. An empty or absent value means every engine. */
    fun engineIds(raw: String?): List<String> =
        (raw ?: PROMPT_SET_ALL).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf(PROMPT_SET_ALL) }

    fun workloadsFor(
        set: String,
        corpusPromptIds: List<String>,
        maxNewTokens: Int,
    ): List<WorkloadConfig> {
        val ids = when (set) {
            PROMPT_SET_ALL -> corpusPromptIds
            PROMPT_SET_DEFAULT -> DEFAULT_PROMPT_IDS
            else -> set.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        return ids.map { WorkloadConfig(it, budgetFor(it, maxNewTokens)) }
    }

    /** The token budget a prompt runs with: the requested one, raised to fit the workload. */
    fun budgetFor(promptId: String, requested: Int): Int =
        MINIMUM_BUDGETS.firstOrNull { (prefix, _) -> promptId.startsWith(prefix) }
            ?.let { (_, minimum) -> maxOf(requested, minimum) }
            ?: requested
}
