package io.github.lemcoder.koinference.benchmark.app;

/**
 * Progress and results of a benchmark run, from the backend's process back to the app's.
 *
 * Every method is oneway: a run takes minutes, and the service must not block on the app's binder
 * thread to report a line of progress.
 */
interface IBenchmarkCallback {

    /** A line worth showing while the run is going, e.g. "llama.cpp: short_generation_v1 3/3". */
    oneway void onProgress(String message);

    /**
     * The finished run, as the JSON of a benchmark file.
     *
     * JSON rather than a Parcelable because :benchmark:core already serialises this shape and a
     * hand-written Parcelable would be a second definition of it, free to disagree.
     */
    oneway void onFinished(String resultsJson);

    oneway void onFailed(String message);
}
