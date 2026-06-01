#include <jni.h>
#include <cstring>
#include "koinference_facade.h"

// JNI package: io.github.lemcoder.koinference.llamacpp.internal
// JNI class:   LlamaCppBridgeJni
#define JNI_METHOD(name) \
    Java_io_github_lemcoder_koinference_llamacpp_internal_LlamaCppBridgeJni_##name

static constexpr int GEN_BUF_SIZE = 1 << 20; // 1 MiB generation output buffer

/* ── backend ──────────────────────────────────────────────────────────────── */

extern "C" JNIEXPORT void JNICALL
JNI_METHOD(backendInit)(JNIEnv*, jobject) {
    koi_backend_init();
}

extern "C" JNIEXPORT void JNICALL
JNI_METHOD(backendFree)(JNIEnv*, jobject) {
    koi_backend_free();
}

extern "C" JNIEXPORT jstring JNICALL
JNI_METHOD(systemInfo)(JNIEnv* env, jobject) {
    return env->NewStringUTF(koi_system_info());
}

/* ── model ────────────────────────────────────────────────────────────────── */

extern "C" JNIEXPORT jlong JNICALL
JNI_METHOD(modelLoad)(JNIEnv* env, jobject, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    KoiModel* model = koi_model_load(path);
    env->ReleaseStringUTFChars(jpath, path);
    return reinterpret_cast<jlong>(model);
}

extern "C" JNIEXPORT void JNICALL
JNI_METHOD(modelFree)(JNIEnv*, jobject, jlong handle) {
    koi_model_free(reinterpret_cast<KoiModel*>(handle));
}

/* ── session ──────────────────────────────────────────────────────────────── */

extern "C" JNIEXPORT jlong JNICALL
JNI_METHOD(sessionCreate)(JNIEnv*, jobject, jlong modelHandle,
                          jint nCtx, jint nThreads, jint nPredict,
                          jfloat temp, jint topK, jfloat minP) {
    KoiSessionParams params = {nCtx, nThreads, nPredict, temp, topK, minP};
    KoiSession* session = koi_session_create(reinterpret_cast<KoiModel*>(modelHandle), params);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL
JNI_METHOD(sessionFree)(JNIEnv*, jobject, jlong handle) {
    koi_session_free(reinterpret_cast<KoiSession*>(handle));
}

/* ── generation ───────────────────────────────────────────────────────────── */

extern "C" JNIEXPORT jstring JNICALL
JNI_METHOD(generate)(JNIEnv* env, jobject, jlong sessionHandle,
                     jstring jSystemPrompt, jstring jUserPrompt, jstring jGrammar) {
    auto* session = reinterpret_cast<KoiSession*>(sessionHandle);

    const char* systemPrompt = jSystemPrompt ? env->GetStringUTFChars(jSystemPrompt, nullptr) : nullptr;
    const char* userPrompt   = env->GetStringUTFChars(jUserPrompt, nullptr);
    const char* grammar      = jGrammar ? env->GetStringUTFChars(jGrammar, nullptr) : nullptr;

    char* buf = new char[GEN_BUF_SIZE];
    const int len = koi_generate(session, systemPrompt, userPrompt, grammar, buf, GEN_BUF_SIZE);

    if (jSystemPrompt) env->ReleaseStringUTFChars(jSystemPrompt, systemPrompt);
    env->ReleaseStringUTFChars(jUserPrompt, userPrompt);
    if (jGrammar) env->ReleaseStringUTFChars(jGrammar, grammar);

    jstring result = (len >= 0) ? env->NewStringUTF(buf) : env->NewStringUTF("");
    delete[] buf;
    return result;
}

/* ── embeddings ───────────────────────────────────────────────────────────── */

extern "C" JNIEXPORT jfloatArray JNICALL
JNI_METHOD(embed)(JNIEnv* env, jobject, jlong sessionHandle, jstring jtext) {
    auto* session = reinterpret_cast<KoiSession*>(sessionHandle);

    const char* text = env->GetStringUTFChars(jtext, nullptr);

    // Temporary float buffer (4096 dims max; resize if needed)
    constexpr int MAX_DIMS = 8192;
    float buf[MAX_DIMS];
    const int dims = koi_embed(session, text, buf, MAX_DIMS);
    env->ReleaseStringUTFChars(jtext, text);

    if (dims < 0) return env->NewFloatArray(0);

    jfloatArray result = env->NewFloatArray(dims);
    env->SetFloatArrayRegion(result, 0, dims, buf);
    return result;
}
