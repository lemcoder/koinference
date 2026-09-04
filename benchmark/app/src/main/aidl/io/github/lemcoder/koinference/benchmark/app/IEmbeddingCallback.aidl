package io.github.lemcoder.koinference.benchmark.app;

/** Vectors, from the engine's process back to the app's. */
interface IEmbeddingCallback {

    /**
     * All the vectors of one request, flattened.
     *
     * One flat array rather than a list of arrays: AIDL would marshal a List<float[]> element by
     * element, and a batch of 384-dimension vectors is exactly the shape that makes that expensive.
     * [dimensions] slices it back apart.
     */
    oneway void onEmbeddings(in float[] flat, int dimensions, int promptTokens);

    oneway void onFailed(String message);
}
