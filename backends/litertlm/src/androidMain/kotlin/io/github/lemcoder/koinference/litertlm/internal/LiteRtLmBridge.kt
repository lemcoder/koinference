package io.github.lemcoder.koinference.litertlm.internal

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Engine as LiteRtLmSdkEngine
import com.google.ai.edge.litertlm.Conversation as LiteRtLmSdkConversation

// Android does not go through native/facade at all. The AAR's liblitertlm_jni.so exports its
// JNI entry points and nothing else, so there is no C API here to bind — this leg is Google's
// own Kotlin API, and the AAR brings its own .so along, which is why this module needs no
// externalNativeBuild the way :backends:llamacpp does.

internal actual class LiteRtLmEngine(val engine: LiteRtLmSdkEngine)
internal actual class LiteRtLmConversation(val conversation: LiteRtLmSdkConversation)

internal actual fun openEngine(
    path: String,
    cacheDir: String?,
    backend: Int,
    nThreads: Int,
    maxTokens: Int,
): LiteRtLmEngine {
    val engine = LiteRtLmSdkEngine(
        EngineConfig(
            modelPath = path,
            backend = when (backend) {
                BACKEND_GPU -> Backend.GPU()
                else -> Backend.CPU(threadCount = nThreads.takeIf { it > 0 })
            },
            maxNumTokens = maxTokens.takeIf { it > 0 },
            cacheDir = cacheDir,
        )
    )
    // Loading is explicit here, unlike the facade where koilm_model_load does both. Failure
    // arrives as an exception rather than a null handle.
    engine.initialize()
    return LiteRtLmEngine(engine)
}

internal actual fun closeEngine(engine: LiteRtLmEngine) {
    engine.engine.close()
}

internal actual fun openConversation(
    engine: LiteRtLmEngine,
    maxTokens: Int,
    topK: Int,
    topP: Float,
    temp: Float,
    systemPrompt: String?,
): LiteRtLmConversation {
    val conversation = engine.engine.createConversation(
        ConversationConfig(
            systemInstruction = systemPrompt
                ?.takeIf { it.isNotEmpty() }
                ?.let { Contents.of(it) },
            samplerConfig = SamplerConfig(
                topK = topK,
                topP = topP.toDouble(),
                temperature = temp.toDouble(),
                seed = 0,
            ),
            maxOutputToken = maxTokens.takeIf { it > 0 },
            // The facade arms constrained decoding on the conversation config for the same
            // reason: the schema arrives per message, so this has to be on beforehand.
            enableResponseFormat = true,
        )
    )
    return LiteRtLmConversation(conversation)
}

internal actual fun closeConversation(conversation: LiteRtLmConversation) {
    conversation.conversation.close()
}

internal actual fun generate(
    conversation: LiteRtLmConversation,
    prompt: String,
    jsonSchema: String?,
): String {
    // Built explicitly rather than via the String overload: with named arguments that
    // overload is ambiguous against the Message one, and this is the same {role, content}
    // pair the facade assembles by hand on the other leg.
    val reply = conversation.conversation.sendMessage(
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
