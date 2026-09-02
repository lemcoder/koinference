package io.github.lemcoder.koinference.benchmark.app.client

import io.github.lemcoder.koinference.benchmark.app.service.CeraService
import io.github.lemcoder.koinference.benchmark.app.service.ExecuTorchService
import io.github.lemcoder.koinference.benchmark.app.service.LiteRtLmService
import io.github.lemcoder.koinference.benchmark.app.service.LlamaCppService

/**
 * The engines this app ships, and the service class each one lives in.
 *
 * The only place the app names a backend. Adding one is adding a service, a manifest entry with its
 * own `android:process`, and a line here.
 *
 * [label] is for the screen before anything is bound; the engine's real id comes from the service
 * itself, so this cannot drift into a second answer to "which backend is this".
 */
enum class BackendProcess(val label: String, val serviceClass: Class<*>) {
    LLAMA_CPP("llama.cpp", LlamaCppService::class.java),
    LITE_RT_LM("LiteRT-LM", LiteRtLmService::class.java),
    CERA("Cera", CeraService::class.java),
    EXECUTORCH("ExecuTorch", ExecuTorchService::class.java),
}
