package io.github.lemcoder.koinference.litertlm.internal

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import io.github.lemcoder.koinference.InferenceBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
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
        var sampler = if (options.greedy) {
            // No sampler type in the public config, so argmax is spelled top-k of 1. Temperature
            // is irrelevant once there is one candidate, and is left at the runtime's own value
            // rather than passed as 0, which this sampler does not treat as "no randomness".
            SamplerConfig(topK = 1, topP = options.topP.toDouble(), temperature = 1.0)
        } else {
            SamplerConfig(
                topK = options.topK,
                topP = options.topP.toDouble(),
                temperature = options.temperature.toDouble(),
            )
        }
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

    override fun generate(prompt: String, jsonSchema: String?): String {
        // Built explicitly rather than via the String overload: with named arguments that
        // overload is ambiguous against the Message one, and this is the same {role, content}
        // pair the facade assembles by hand on the other leg.
        val reply = conversation.sendMessage(
            message = Message.user(prompt),
            responseFormat = jsonSchema?.let { ResponseFormat.json(it) },
        )
        // The SDK hands back a parsed Message, so there is no envelope to unwrap the way the
        // facade's raw JSON needs — only the text parts to concatenate, which is the same rule
        // extractResponseText applies on the native side.
        return reply.contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString("") { it.text }
    }

    /**
     * The SDK's own streaming flow, with each message's text parts concatenated.
     *
     * Nothing is timed here. The caller timestamps emissions, which is the same thing it does
     * to the llama.cpp binding's chunks — one clock, one code path, two engines.
     */
    override fun stream(prompt: String, jsonSchema: String?): Flow<String> =
        conversation.sendMessageAsync(
            message = Message.user(prompt),
            responseFormat = jsonSchema?.let { ResponseFormat.json(it) },
        ).map { chunk ->
            chunk.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
        }.filter { it.isNotEmpty() }

    override fun close() {
        conversation.close()
    }
}
