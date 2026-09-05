package io.github.lemcoder.koinference.executorch.internal

/** ExecuTorch publishes an Android AAR and nothing else, so there is one leg to answer for. */
internal actual fun platformBridge(): ExecuTorchBridge = LlmModuleBridge
