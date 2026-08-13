package io.github.lemcoder.koinference.litertlm.internal

// These live apart from LiteRtLmBridge.kt on purpose. That file holds nothing but expect
// declarations, which generate no JVM class — so androidMain can keep the same filename, the
// way :backends:llamacpp does. Adding a top-level constant to it makes both files compile to
// LiteRtLmBridgeKt and the Android build fails with a duplicate JVM class name.

/** Backend selector shared by both legs, matching the facade's KOILM_BACKEND_* values. */
internal const val BACKEND_CPU = 0
internal const val BACKEND_GPU = 1
