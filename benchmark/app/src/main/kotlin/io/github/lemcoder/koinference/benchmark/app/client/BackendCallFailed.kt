package io.github.lemcoder.koinference.benchmark.app.client

/** The service reported a failure; the message is the engine's own words. */
class BackendCallFailed(message: String) : RuntimeException(message)
