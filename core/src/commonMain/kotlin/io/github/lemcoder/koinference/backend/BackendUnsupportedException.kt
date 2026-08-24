package io.github.lemcoder.koinference.backend

/**
 * A backend was asked for a model on hardware it cannot run on. Thrown before any weights are read.
 *
 * @property backendId which engine refused, so a caller holding several can drop that one.
 * @property reason what the device is missing, from [Backend.unsupportedReason].
 */
class BackendUnsupportedException(
    val backendId: String,
    val reason: String,
) : IllegalStateException("$backendId cannot run on this device: $reason")
