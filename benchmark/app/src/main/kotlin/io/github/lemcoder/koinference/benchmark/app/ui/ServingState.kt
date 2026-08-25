package io.github.lemcoder.koinference.benchmark.app.ui

import io.github.lemcoder.koinference.benchmark.app.client.BackendProcess

/** Whether the HTTP server is up, and what it is serving. */
sealed interface ServingState {

    data object Stopped : ServingState

    data class Starting(val process: BackendProcess) : ServingState

    /** [url] is what a client on this network dials. */
    data class Serving(val process: BackendProcess, val modelName: String, val url: String) : ServingState

    data class Failed(val message: String) : ServingState
}
