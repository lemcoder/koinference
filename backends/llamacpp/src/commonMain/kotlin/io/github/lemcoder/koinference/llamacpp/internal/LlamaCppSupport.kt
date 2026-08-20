package io.github.lemcoder.koinference.llamacpp.internal

/**
 * Why this device cannot run llama.cpp, or null when it can.
 *
 * Only Android answers with anything. The ARM kernels this backend decodes with are chosen when
 * ggml is compiled — `#if defined(__ARM_FEATURE_DOTPROD)`, with no `getauxval` anywhere in
 * `ggml-cpu` — so a device without the dot-product extension does not fall back, it takes SIGILL
 * partway through a decode. On every other target the binary is built for the machine that runs it.
 */
internal expect fun llamaCppUnsupportedReason(): String?
