package io.github.lemcoder.koinference.litertlm.internal

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import android.os.SystemClock
import io.github.lemcoder.koinference.GenerationTelemetry
import io.github.lemcoder.koinference.InferenceBackend
import io.github.lemcoder.koinference.TelemetrySource
import kotlinx.coroutines.runBlocking
import com.google.ai.edge.litertlm.Conversation as SdkConversation
import com.google.ai.edge.litertlm.Engine as SdkEngine

// Android does not go through native/facade at all. The AAR's liblitertlm_jni.so exports its
// JNI entry points and nothing else, so there is no C API here to bind — this leg is Google's
// own Kotlin API, and the AAR brings its own .so along, which is why this module needs no
// externalNativeBuild the way :backends:llamacpp does.

internal actual fun platformBridge(): LiteRtLmBridge = SdkBridge

private object SdkBridge : LiteRtLmBridge {
    override fun openEngine(options: EngineOptions): LiteRtLmEngine {
        val engine = SdkEngine(
            EngineConfig(
                modelPath = options.modelPath,
                backend = when (options.backend) {
                    InferenceBackend.GPU -> Backend.GPU()
                    InferenceBackend.CPU -> Backend.CPU(
                        threadCount = options.nThreads.takeIf { it > 0 },
                    )
                },
                maxNumTokens = options.maxTokens.takeIf { it > 0 },
                cacheDir = options.cacheDir,
            )
        )
        // Loading is explicit here, unlike the facade where koilm_model_load does both. Failure
        // arrives as an exception rather than a null handle.
        engine.initialize()
        return SdkEngineHandle(engine)
    }
}

private class SdkEngineHandle(private val engine: SdkEngine) : LiteRtLmEngine {

    override fun openConversation(options: ConversationOptions): LiteRtLmConversation {
        // SamplerConfig's seed defaults to 0, i.e. deterministic, where the facade leaves
        // LiteRT-LM to seed itself. Copying onto the default rather than naming seed in the
        // constructor keeps an unset seed meaning the same thing on both legs.
        var sampler = SamplerConfig(
            topK = options.topK,
            topP = options.topP.toDouble(),
            temperature = options.temperature.toDouble(),
        )
        options.seed?.let { sampler = sampler.copy(seed = it) }

        val conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = options.systemPrompt
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { Contents.of(it) },
                samplerConfig = sampler,
                maxOutputToken = options.maxTokens.takeIf { it > 0 },
                // The facade arms constrained decoding on the conversation config for the same
                // reason: the schema arrives per message, so this has to be on beforehand.
                enableResponseFormat = true,
            )
        )
        return SdkConversationHandle(conversation)
    }

    override fun close() {
        engine.close()
    }
}

private class SdkConversationHandle(
    private val conversation: SdkConversation,
) : LiteRtLmConversation {

    override fun generate(prompt: String, jsonSchema: String?): GeneratedReply {
        // Streaming rather than the blocking overload, only so that the first chunk can be
        // timestamped as it arrives. The reply is assembled from the chunks and is the same
        // text the blocking call returns.
        //
        // Built explicitly rather than via the String overload: with named arguments that
        // overload is ambiguous against the Message one, and this is the same {role, content}
        // pair the facade assembles by hand on the other leg.
        val start = SystemClock.elapsedRealtimeNanos()
        var firstChunkAt: Long? = null
        val text = StringBuilder()

        runBlocking {
            conversation.sendMessageAsync(
                message = Message.user(prompt),
                responseFormat = jsonSchema?.let { ResponseFormat.json(it) },
            ).collect { chunk ->
                if (firstChunkAt == null) firstChunkAt = SystemClock.elapsedRealtimeNanos()
                // The SDK hands back parsed Messages, so there is no envelope to unwrap the way
                // the facade's raw JSON needs — only the text parts to concatenate, which is the
                // same rule extractResponseText applies on the native side.
                chunk.contents.contents
                    .filterIsInstance<Content.Text>()
                    .forEach { text.append(it.text) }
            }
        }
        val endedAt = SystemClock.elapsedRealtimeNanos()

        return GeneratedReply(text.toString(), telemetry(start, firstChunkAt, endedAt))
    }

    /**
     * Prefers what the engine measured over what this binding can see.
     *
     * `getBenchmarkInfo()` is `@ExperimentalApi` and is a function, not a property — reading it
     * as `conversation.benchmarkInfo` does not compile, which is easy to mistake for the
     * accessor being unavailable. It is available.
     *
     * What is not guaranteed is that it holds anything: nothing in the public `EngineConfig`
     * turns benchmarking on, so on a runtime built without it every field reads zero. Zero
     * time-to-first-token is not a measurement, so it is rejected and the streamed first chunk
     * — a real first-token observation one layer further out — is reported instead, labelled
     * [TelemetrySource.STREAM_FIRST_CHUNK] so the two are never averaged.
     */
    @OptIn(ExperimentalApi::class)
    private fun telemetry(startNanos: Long, firstChunkNanos: Long?, endNanos: Long): GenerationTelemetry {
        val engine = runCatching { conversation.getBenchmarkInfo() }.getOrNull()
        val engineTtftMs = engine?.timeToFirstTokenInSecond?.takeIf { it > 0.0 }?.let { it * 1000.0 }

        if (engine != null && engineTtftMs != null) {
            return GenerationTelemetry(
                source = TelemetrySource.ENGINE,
                timeToFirstTokenMs = engineTtftMs,
                // Prefill and decode durations are not reported directly; the rates and counts
                // are, and deriving a duration from a rate would invent precision.
                prefillMs = null,
                decodeMs = null,
                promptTokens = engine.lastPrefillTokenCount.takeIf { it > 0 },
                decodeTokens = engine.lastDecodeTokenCount.takeIf { it > 0 },
                prefillTokensPerSecond = engine.lastPrefillTokensPerSecond.takeIf { it > 0.0 },
                decodeTokensPerSecond = engine.lastDecodeTokensPerSecond.takeIf { it > 0.0 },
                engineInitMs = engine.initTimeInSecond.takeIf { it > 0.0 }?.let { it * 1000.0 },
            )
        }

        return GenerationTelemetry(
            source = TelemetrySource.STREAM_FIRST_CHUNK,
            timeToFirstTokenMs = firstChunkNanos?.let { (it - startNanos) / 1_000_000.0 },
            // Prefill is not separable here: the first chunk is the only observable event and
            // it already includes prefill.
            prefillMs = null,
            decodeMs = firstChunkNanos?.let { (endNanos - it) / 1_000_000.0 },
            // Chunks are not tokens, and neither is a whitespace split of the reply. With the
            // engine's counters empty this binding has no tokenizer to ask, so a count here
            // would be invented.
            promptTokens = null,
            decodeTokens = null,
            prefillTokensPerSecond = null,
            decodeTokensPerSecond = null,
            engineInitMs = null,
        )
    }

    override fun close() {
        conversation.close()
    }
}
