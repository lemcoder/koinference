#pragma once

#ifdef __cplusplus
extern "C" {
#endif

/** Opaque engine handle (a loaded .litertlm/.task model). */
typedef struct KoiLmEngine KoiLmEngine;

/** Opaque conversation handle (chat state over an engine). */
typedef struct KoiLmConversation KoiLmConversation;

/** Backend selector, matching io.github.lemcoder.koinference.InferenceBackend. */
enum {
    KOILM_BACKEND_CPU = 0,
    KOILM_BACKEND_GPU = 1
};

/**
 * Sampling parameters for a conversation.
 *
 * Four 4-byte fields, no padding. The JVM leg packs this as a ByteBuffer, so the
 * field order and count are part of the contract — see LiteRtLmBridge.
 */
typedef struct {
    int   max_tokens;  /**< Max tokens to generate; <= 0 = model default. */
    int   top_k;       /**< Top-k sampling; 0 = disabled. */
    float top_p;       /**< Top-p sampling; 0.0 = disabled. */
    float temp;        /**< Sampling temperature. */
} KoiLmSessionParams;

/* ── diagnostics ──────────────────────────────────────────────────────────── */

/**
 * Message describing why the most recent call returned NULL or -1.
 * Owned by the facade, valid until the next failing call on this thread.
 * Never NULL; empty when nothing has failed.
 */
const char* koilm_last_error(void);

/* ── model ────────────────────────────────────────────────────────────────── */

/**
 * Load a model and create an engine.
 *
 * @param path       Path to a .litertlm or .task file. Raw .tflite is rejected
 *                   by LiteRT-LM itself.
 * @param cache_dir  Writable directory that speeds up subsequent loads; may be NULL.
 * @param backend    KOILM_BACKEND_CPU or KOILM_BACKEND_GPU.
 * @param n_threads  CPU thread count; 0 = leave at the engine default.
 * @param max_tokens Engine-wide token budget (context); 0 = model default.
 * @return handle, or NULL on failure. Caller owns; free with koilm_model_free().
 */
KoiLmEngine* koilm_model_load(
    const char* path,
    const char* cache_dir,
    int         backend,
    int         n_threads,
    int         max_tokens
);

/** Free an engine. Safe to call with NULL. */
void koilm_model_free(KoiLmEngine* engine);

/* ── conversation ─────────────────────────────────────────────────────────── */

/** Returns sensible defaults for KoiLmSessionParams. */
KoiLmSessionParams koilm_default_session_params(void);

/**
 * Open a conversation over an engine.
 *
 * @param system_prompt System message; may be NULL or empty.
 * @return handle, or NULL on failure. Caller owns; free with koilm_session_free().
 */
KoiLmConversation* koilm_session_create(
    KoiLmEngine*       engine,
    KoiLmSessionParams params,
    const char*        system_prompt
);

/** Free a conversation. Safe to call with NULL. */
void koilm_session_free(KoiLmConversation* conversation);

/* ── generation ───────────────────────────────────────────────────────────── */

/**
 * Send one user message and wait for the reply (blocking).
 *
 * The reply is written as the JSON object LiteRT-LM produces, not bare text —
 * extracting the assistant content is left to the Kotlin side, which has a JSON
 * parser and does not need one linked into the facade.
 *
 * @param json_schema JSON schema for constrained decoding; NULL = unconstrained.
 *                    Served by llguidance, which is statically linked into the
 *                    prebuilt runtime.
 * @param out_buf     Destination for the null-terminated JSON response.
 * @param buf_size    Size of out_buf in bytes, including the null terminator.
 * @return            Bytes written (excluding the null terminator), or -1 on
 *                    error. If the reply does not fit, -1 is returned and
 *                    koilm_last_error() reports the size that was needed.
 */
int koilm_generate(
    KoiLmConversation* conversation,
    const char*        user_prompt,
    const char*        json_schema,
    char*              out_buf,
    int                buf_size
);

#ifdef __cplusplus
}
#endif
