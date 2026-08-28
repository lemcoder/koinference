package io.github.lemcoder.koinference.cera

import kotlin.test.Test
import uniffi.cera_ffi.BackendPreference
import uniffi.cera_ffi.CeraEngine
import uniffi.cera_ffi.ChatMessage
import uniffi.cera_ffi.EngineConfig
import uniffi.cera_ffi.GenerateOpts
import uniffi.cera_ffi.SessionConfig

class BackendProbe {

    @Test
    fun probe() {
        val path = System.getenv("KOI_TEST_GGUF") ?: return

        for (pref in listOf(BackendPreference.CPU, BackendPreference.AUTO, BackendPreference.METAL)) {
            val engine = runCatching { CeraEngine.fromPath(path, EngineConfig(0uL, pref, null)) }
                .getOrNull()
            if (engine == null) {
                println("PROBE $pref: not available on this machine")
                continue
            }

            val prompt = engine.applyChatTemplate(listOf(ChatMessage("user", "Count to twenty.")), true)
            val opts = GenerateOpts(maxTokens = 64u, temperature = 0.0f)

            // Warmup, then measure: the first generation on a fresh engine is not the steady state.
            val warm = engine.newSession(SessionConfig())
            warm.appendText(prompt)
            warm.generate(opts)

            val session = engine.newSession(SessionConfig())
            session.appendText(prompt)
            val started = System.nanoTime()
            val out = session.generate(opts)
            val ms = (System.nanoTime() - started) / 1_000_000.0
            val tokens = out.tokens.size
            println("PROBE $pref: $tokens tokens in ${"%.0f".format(ms)}ms = ${"%.1f".format(tokens * 1000.0 / ms)} tok/s")
        }
    }
}
