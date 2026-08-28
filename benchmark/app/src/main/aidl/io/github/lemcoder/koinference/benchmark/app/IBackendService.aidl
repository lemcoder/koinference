package io.github.lemcoder.koinference.benchmark.app;

import io.github.lemcoder.koinference.benchmark.app.IBenchmarkCallback;
import io.github.lemcoder.koinference.benchmark.app.IGenerationCallback;
import io.github.lemcoder.koinference.benchmark.app.IStatusCallback;

/**
 * One inference engine, alone in its own process.
 *
 * The process boundary is the point rather than an implementation detail. A model's memory is most
 * of what anyone wants to measure, and in a shared process it arrives mixed with the UI toolkit,
 * Compose and an HTTP server. One engine per process means `dumpsys meminfo` shows a line that is
 * the model, and means a native crash takes down the engine rather than the app.
 *
 * The quick calls are synchronous because they touch no model. Everything that loads weights or
 * decodes is oneway with a callback: those take seconds to minutes, and a binder thread blocked
 * that long is a frozen UI.
 */
interface IBackendService {

    /** Stable engine id, e.g. `llama.cpp`. The same string the results file carries. */
    String backendId();

    /**
     * Why this device cannot run this engine, or null when it can.
     *
     * Asked before anything is loaded: llama.cpp's kernels are chosen at compile time, so a CPU
     * without the dot-product extension takes SIGILL rather than failing an API call.
     */
    String unsupportedReason();

    /** Model files on this device that this engine can read, by absolute path. */
    List<String> modelPaths();

    /**
     * Run the benchmark suite in *this* process and report the results file.
     *
     * Here rather than in the app, so no timing includes a binder round trip and the memory
     * readings are the engine's own process. What crosses the boundary is a finished run.
     */
    oneway void runBenchmark(String modelPath, String configJson, IBenchmarkCallback callback);

    /** Load a model and keep it, for serving over the network. */
    oneway void load(String modelPath, String optionsJson, IStatusCallback callback);

    /** Generate from the loaded model. Fails if nothing is loaded. */
    oneway void generate(String requestJson, IGenerationCallback callback);

    /**
     * This process's own memory, as JSON.
     *
     * Asked over the boundary rather than read by the caller: the app process holds Compose and an
     * HTTP server, and its PSS says nothing about a model. Read here it is the engine and the
     * weights.
     */
    String processMemory();

    /** Release the loaded model. Idempotent. */
    oneway void unload(IStatusCallback callback);
}
