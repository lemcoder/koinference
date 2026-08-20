package io.github.lemcoder.koinference.backend

/**
 * A backend was asked for a model on hardware it cannot run on.
 *
 * Thrown before any weights are read, because the alternative is worse than an exception: an engine
 * built for instructions this CPU does not have takes SIGILL in the middle of a decode, which
 * reaches the application as a process death with no stack trace and no way to catch it.
 *
 * @property backendId which engine refused, so a caller holding several can drop that one and go on
 *           with the rest.
 * @property reason what the device is missing, in words a bug report can carry.
 */
class BackendUnsupportedException(
    val backendId: String,
    val reason: String,
) : IllegalStateException("$backendId cannot run on this device: $reason")
