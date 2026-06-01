#pragma once

#ifdef __cplusplus
extern "C" {
#endif

/** Opaque model handle. */
typedef struct KoiModel KoiModel;

/** Opaque session handle (context + batch + chat templates). */
typedef struct KoiSession KoiSession;

/** Parameters used when creating a session. */
typedef struct {
    int   n_ctx;      /**< Context size in tokens; 0 = use model's trained size. */
    int   n_threads;  /**< CPU thread count; 0 = auto-detect. */
    int   n_predict;  /**< Max tokens to generate; -1 = unlimited. */
    float temp;       /**< Sampling temperature. */
    int   top_k;      /**< Top-k sampling; 0 = disabled. */
    float min_p;      /**< Min-p sampling; 0.0 = disabled. */
} KoiSessionParams;

/* ── backend ──────────────────────────────────────────────────────────────── */

/** Initialize llama.cpp backends. Must be called once before any other API. */
void koi_backend_init(void);

/** Release backend resources. Call once at shutdown. */
void koi_backend_free(void);

/** Null-terminated string describing active CPU/GPU features. */
const char* koi_system_info(void);

/* ── model ────────────────────────────────────────────────────────────────── */

/**
 * Load a GGUF model file.
 * @return handle, or NULL on failure. Caller owns; free with koi_model_free().
 */
KoiModel* koi_model_load(const char* path);

/** Free a loaded model. Safe to call with NULL. */
void koi_model_free(KoiModel* model);

/* ── session ──────────────────────────────────────────────────────────────── */

/** Returns sensible defaults for KoiSessionParams. */
KoiSessionParams koi_default_session_params(void);

/**
 * Create an inference session for a model.
 * @return handle, or NULL on failure. Caller owns; free with koi_session_free().
 */
KoiSession* koi_session_create(KoiModel* model, KoiSessionParams params);

/** Free a session. Safe to call with NULL. */
void koi_session_free(KoiSession* session);

/* ── generation ───────────────────────────────────────────────────────────── */

/**
 * Generate a response (blocking).
 *
 * @param session       Active session.
 * @param system_prompt System prompt; may be NULL or empty.
 * @param user_prompt   User prompt. Must not be NULL.
 * @param grammar       BNF grammar string for constrained generation; NULL = unconstrained.
 * @param out_buf       Destination buffer for the null-terminated response.
 * @param buf_size      Size of out_buf in bytes (including space for the null terminator).
 * @return              Bytes written (excluding null terminator), or -1 on error.
 */
int koi_generate(
    KoiSession*  session,
    const char*  system_prompt,
    const char*  user_prompt,
    const char*  grammar,
    char*        out_buf,
    int          buf_size
);

/* ── embeddings ───────────────────────────────────────────────────────────── */

/**
 * Compute text embeddings.
 *
 * @param session   Active session (model must be an embedding model).
 * @param text      Input text.
 * @param out_buf   Pre-allocated buffer of at least embed_dim floats.
 * @param buf_size  Capacity of out_buf in number of floats.
 * @return          Embedding dimensions, or -1 on error.
 */
int koi_embed(KoiSession* session, const char* text, float* out_buf, int buf_size);

#ifdef __cplusplus
}
#endif
