package io.github.lemcoder.koinference.cera.internal

import io.github.lemcoder.koinference.requireModelFits

/** Both legs link the same UniFFI binding; only the packaged natives differ. */
// Wrapped, not UniffiBridge directly: the memory guard is Android-only and UniffiBridge is shared
// verbatim with the JVM leg (a desktop has swap and does not want it). The wrapper adds the guard
// on this leg without touching that shared file.
internal actual fun platformBridge(): CeraBridge = object : CeraBridge {
    override fun openModel(options: CeraModelOptions): CeraModel {
        requireModelFits(options.modelPath)
        return UniffiBridge.openModel(options)
    }
}
