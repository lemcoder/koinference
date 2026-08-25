package io.github.lemcoder.koinference.benchmark.app;

/** One generation, streamed from the backend's process to whoever asked for it. */
interface IGenerationCallback {

    /** One chunk of reply text, as it arrives. Non-text parts of a reply are not sent. */
    oneway void onChunk(String text);

    /** The generation finished; carries the token count and timings as JSON. */
    oneway void onFinished(String statsJson);

    oneway void onFailed(String message);
}
