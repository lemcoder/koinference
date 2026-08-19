package io.github.lemcoder.koinference.benchmark

import kotlinx.coroutines.CancellationException

/**
 * Runs the protocol.
 *
 * For each engine × workload, in order: initialize, load the model, warm up, measure, then
 * optionally sustain, then tear down. Warmup samples are kept in their own field and never
 * enter [BenchmarkRecord.samples] — the first iteration of a cold process pays for page cache
 * misses, JIT and delegate setup, and averaging it into the rest hides both effects.
 *
 * A failure fails its record and the run continues to the next combination. The record keeps
 * whatever was collected up to that point, marked [BenchmarkStatus.FAILED] with the reason, so
 * a partially-collected record can never be read as a measurement.
 *
 * What this class cannot do is give each engine a fresh process. Two engines measured in one
 * process share a heap that the first one has already grown and an SoC the first one has
 * already heated. The Android runner therefore invokes one engine per instrumentation run;
 * see its documentation. Running `engine=all` in a single process is supported for
 * convenience and records a note saying the results are contaminated.
 */
class BenchmarkRunner(
    private val config: BenchmarkConfig,
    private val prompts: PromptCorpus,
    private val probe: PlatformProbe = platformProbe(),
    private val log: (String) -> Unit = {},
    /**
     * The engines [BenchmarkConfig.engineIds] may name.
     *
     * A parameter rather than a call to [availableEngines] inside, so that everything this class
     * decides — warmup kept out of the samples, what a failed record contains, which notes a
     * record carries — can be exercised with a fake engine instead of two real runtimes and a
     * pair of model files. The default is the real registry, so callers see no difference.
     */
    private val engines: List<BenchmarkInferenceEngine> = availableEngines(),
) {

    suspend fun run(): BenchmarkFile {
        val selected = resolveEngines()
        val records = mutableListOf<BenchmarkRecord>()

        for ((index, engine) in selected.withIndex()) {
            for (workload in config.workloads) {
                records += runOne(
                    engine = engine,
                    workload = workload,
                    // Only the first engine in a process sees an untouched process.
                    contaminated = index > 0,
                )
            }
        }

        return BenchmarkFile(
            runId = config.benchmarkRunId,
            // From the caller, which is the only thing that knows which matrix entry this
            // process was launched as: nothing on the device reports its FTL identity.
            device = probe.describeDevice().copy(
                ftlModelId = config.ftlModelId,
                ftlVersion = config.ftlVersion,
            ),
            records = records,
        )
    }

    private fun resolveEngines(): List<BenchmarkInferenceEngine> =
        if (config.engineIds.singleOrNull() == "all") engines
        else config.engineIds.map { id ->
            engines.firstOrNull { it.id == id }
                ?: error("Unknown engine id '$id'. Available: ${engines.map { it.id }}")
        }

    private suspend fun runOne(
        engine: BenchmarkInferenceEngine,
        workload: WorkloadConfig,
        contaminated: Boolean,
    ): BenchmarkRecord {
        val prompt = prompts.byId(workload.promptId)
        val notes = mutableListOf<String>()
        if (contaminated) {
            notes += "Ran after another engine in the same process: heap, page cache and SoC " +
                "temperature were not in the same state as a cold run. Compare with caution."
        }

        val engineInfo = EngineInfo(
            id = engine.id,
            modelId = config.model.modelId,
            modelVersion = config.model.modelVersion,
            quantization = config.model.quantization,
            modelSha256 = config.model.sha256,
        )
        val workloadInfo = WorkloadInfo(
            promptId = prompt.id,
            promptSha256 = prompt.sha256,
            promptChars = prompt.text.length,
            maxNewTokens = workload.maxNewTokens,
        )

        if (prompt.text.isEmpty()) {
            return BenchmarkRecord(
                engine = engineInfo,
                workload = workloadInfo,
                status = BenchmarkStatus.SKIPPED,
                failureReason = "Prompt '${prompt.id}' is empty in the fixture corpus.",
                notes = notes,
            )
        }

        engine.applyWorkload(workload, config.sampling)
        log("${engine.id} / ${workload.promptId}: starting")

        val memoryBefore = probe.readMemory()
        val batteryBefore = probe.readBattery()
        val thermalBefore = probe.readThermal()
        var memoryAfterLoad: MemorySnapshot? = null
        var memoryAfterWarmup: MemorySnapshot? = null
        var peakPssKb: Long? = memoryBefore?.pssKb
        val warmupSamples = mutableListOf<GenerationSample>()
        val samples = mutableListOf<GenerationSample>()
        var session: BenchmarkInferenceEngine.EngineSession? = null
        var initialization: InitializationMetrics? = null
        var sustained: SustainedMetrics? = null

        fun observePeak(snapshot: MemorySnapshot?) {
            val pss = snapshot?.pssKb ?: return
            peakPssKb = maxOf(peakPssKb ?: pss, pss)
        }

        try {
            val loadStart = probe.monotonicNanos()
            session = engine.initialize(config.model)
            val modelLoadMs = (probe.monotonicNanos() - loadStart) / 1_000_000.0
            memoryAfterLoad = probe.readMemory()
            observePeak(memoryAfterLoad)

            initialization = InitializationMetrics(
                processStartMs = probe.processUptimeMs(),
                modelLoadMs = modelLoadMs,
                tokenizerInitMs = null,
            )

            val request = GenerationRequest(
                promptId = prompt.id,
                prompt = prompt.text,
                maxNewTokens = workload.maxNewTokens,
            )

            repeat(config.warmupIterations) { iteration ->
                warmupSamples += sample(session, request, iteration, ::observePeak)
            }
            memoryAfterWarmup = probe.readMemory()
            observePeak(memoryAfterWarmup)

            repeat(config.measurementIterations) { iteration ->
                samples += sample(session, request, iteration, ::observePeak)
            }

            if (config.sustainedDurationSeconds > 0) {
                sustained = sustain(session, request, ::observePeak)
            }


        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            log("${engine.id} / ${workload.promptId}: FAILED ${failure.message}")
            runCatching { session?.close() }
            return BenchmarkRecord(
                engine = engineInfo,
                workload = workloadInfo,
                status = BenchmarkStatus.FAILED,
                // Type and message: "failed" alone cannot be triaged from an artifact.
                failureReason = "${failure::class.simpleName}: ${failure.message}",
                initialization = initialization,
                // Kept, but under a FAILED status, so nothing downstream averages them.
                samples = samples,
                warmupSamples = warmupSamples,
                memory = memoryMetrics(memoryBefore, memoryAfterLoad, memoryAfterWarmup, peakPssKb, probe.readMemory()),
                thermal = thermalMetrics(thermalBefore, probe.readThermal(), sustained),
                battery = batteryMetrics(batteryBefore, probe.readBattery()),
                engineMetadata = engine.metadata(config.model),
                notes = notes,
            )
        }

        runCatching { session.close() }
        val memoryAfter = probe.readMemory()
        observePeak(memoryAfter)

        // A reply that arrived in one piece was not streamed, whatever the API promised, and its
        // time to first chunk is just its total latency wearing another name. Recorded rather
        // than silently averaged into a TTFT column beside engines that really do stream.
        val buffered = samples.filter { it.chunks == 1 && (it.generatedTokens ?: 0) > 1 }
        if (buffered.isNotEmpty()) {
            notes += "${buffered.size} of ${samples.size} measured iterations arrived as a " +
                "single chunk despite generating several tokens: for those, time to first " +
                "chunk is total latency and is not a first-token measurement."
        }

        // Chunk sizes are an engine's own business, so throughput in chunks/sec is only
        // comparable between engines whose chunks are comparable. Said once per record rather
        // than assumed by whoever reads the table.
        notes += "Chunks are emissions, not tokens: one token per chunk for llama.cpp, " +
            "whatever LiteRT-LM emits for LiteRT-LM. Time to first chunk and total latency " +
            "are directly comparable; chunks/sec is comparable only alongside the chunk count."

        log("${engine.id} / ${workload.promptId}: ${samples.size} samples")

        return BenchmarkRecord(
            engine = engineInfo,
            workload = workloadInfo,
            status = BenchmarkStatus.SUCCESS,
            initialization = initialization,
            samples = samples,
            warmupSamples = warmupSamples,
            memory = memoryMetrics(memoryBefore, memoryAfterLoad, memoryAfterWarmup, peakPssKb, memoryAfter),
            thermal = thermalMetrics(thermalBefore, probe.readThermal(), sustained),
            battery = batteryMetrics(batteryBefore, probe.readBattery()),
            sustained = sustained,
            engineMetadata = engine.metadata(config.model),
            notes = notes,
        )
    }

    private suspend fun sample(
        session: BenchmarkInferenceEngine.EngineSession,
        request: GenerationRequest,
        iteration: Int,
        observePeak: (MemorySnapshot?) -> Unit,
    ): GenerationSample {
        // One measurement function for every engine — see measureGeneration.
        val result = measureGeneration(probe, session.stream(request)) { session.countTokens(it) ?: NO_TOKENIZER }
        // Immediately after generation, which is the closest this can get to the peak without
        // a sampling thread. Named peakPssKb per sample rather than "the" peak for that reason.
        val afterGeneration = probe.readMemory()
        observePeak(afterGeneration)
        return result.toSample(iteration, afterGeneration?.pssKb)
    }

    private suspend fun sustain(
        session: BenchmarkInferenceEngine.EngineSession,
        request: GenerationRequest,
        observePeak: (MemorySnapshot?) -> Unit,
    ): SustainedMetrics {
        val durationNanos = config.sustainedDurationSeconds.toLong() * 1_000_000_000L
        val start = probe.monotonicNanos()
        val decodeRates = mutableListOf<Double>()
        val wallClocks = mutableListOf<Double>()
        val thermalSamples = mutableListOf<ThermalSample>()
        var iterations = 0

        while (probe.monotonicNanos() - start < durationNanos) {
            val result = measureGeneration(probe, session.stream(request)) { session.countTokens(it) ?: NO_TOKENIZER }
            iterations++
            wallClocks += result.totalMs
            result.chunksPerSecond?.let { decodeRates += it }
            observePeak(probe.readMemory())
            probe.readThermal()?.let {
                thermalSamples += it.copy(atMs = (probe.monotonicNanos() - start) / 1_000_000.0)
            }
        }

        return SustainedMetrics(
            requestedSeconds = config.sustainedDurationSeconds,
            actualSeconds = (probe.monotonicNanos() - start) / 1_000_000_000.0,
            iterations = iterations,
            chunksPerSecondSeries = decodeRates,
            wallClockMsSeries = wallClocks,
            thermalSamples = thermalSamples,
        )
    }

    private fun memoryMetrics(
        before: MemorySnapshot?,
        afterLoad: MemorySnapshot?,
        afterWarmup: MemorySnapshot?,
        peakPssKb: Long?,
        after: MemorySnapshot?,
    ): MemoryMetrics? {
        // All-null in, null out: an object full of nulls suggests a failed reading rather than
        // a platform that has none of this.
        if (before == null && afterLoad == null && afterWarmup == null && after == null) return null
        return MemoryMetrics(
            beforeInitPssKb = before?.pssKb,
            afterLoadPssKb = afterLoad?.pssKb,
            afterWarmupPssKb = afterWarmup?.pssKb,
            peakPssKb = peakPssKb,
            afterRunPssKb = after?.pssKb,
            nativeHeapKb = after?.nativeHeapKb,
            javaHeapKb = after?.javaHeapKb,
            rssKb = after?.rssKb,
        )
    }

    private fun thermalMetrics(
        before: ThermalSample?,
        after: ThermalSample?,
        sustained: SustainedMetrics?,
    ): ThermalMetrics? {
        val series = listOfNotNull(before, after) + (sustained?.thermalSamples ?: emptyList())
        if (series.isEmpty()) return null
        val temperatures = series.mapNotNull { it.batteryTemperatureC }
        return ThermalMetrics(
            batteryTemperatureBeforeC = before?.batteryTemperatureC,
            batteryTemperatureAfterC = after?.batteryTemperatureC,
            batteryTemperaturePeakC = temperatures.maxOrNull(),
            thermalStatusBefore = before?.thermalStatus,
            thermalStatusAfter = after?.thermalStatus,
            // Ordered by severity, not alphabetically: the worst state the run reached is the
            // one that explains a throughput drop.
            thermalStatusPeak = series.mapNotNull { it.thermalStatus }.maxByOrNull(::thermalSeverity),
            samples = series,
        )
    }

    private fun thermalSeverity(status: String): Int = when (status) {
        "NONE" -> 0
        "LIGHT" -> 1
        "MODERATE" -> 2
        "SEVERE" -> 3
        "CRITICAL" -> 4
        "EMERGENCY" -> 5
        "SHUTDOWN" -> 6
        else -> -1
    }

    private fun batteryMetrics(before: BatteryReading?, after: BatteryReading?): BatteryMetrics? {
        if (before == null && after == null) return null
        return BatteryMetrics(
            percentBefore = before?.percent,
            percentAfter = after?.percent,
            percentDelta = if (before?.percent != null && after?.percent != null) {
                after.percent - before.percent
            } else {
                null
            },
            charging = after?.charging ?: before?.charging,
            energyCounterNwhBefore = before?.energyCounterNwh,
            energyCounterNwhAfter = after?.energyCounterNwh,
        )
    }

}

/**
 * Maps one measurement onto a sample.
 *
 * Everything here was measured by [measureGeneration], above every engine, with one clock.
 * Nothing is derived from anything an engine said about itself, and nothing is derived from the
 * reply's character count.
 */
private fun GenerationMeasurement.toSample(iteration: Int, peakPssKb: Long?) = GenerationSample(
    iteration = iteration,
    wallClockMs = totalMs,
    ttftMs = timeToFirstChunkMs,
    streamingMs = streamingMs,
    chunks = chunks,
    chunksPerSecond = chunksPerSecond,
    generatedTokens = generatedTokens,
    tokensPerSecond = tokensPerSecond,
    outputChars = text.length,
    peakPssKb = peakPssKb,
)

/**
 * What a count is when the engine has no tokenizer.
 *
 * Negative rather than zero, and filtered out before it reaches a sample: zero tokens is a real
 * outcome for a model that generated nothing, and the two must not collapse into one value.
 */
private const val NO_TOKENIZER = -1
