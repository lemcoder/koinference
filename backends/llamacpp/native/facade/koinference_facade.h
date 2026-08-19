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
 *
 * @param path          Path to the .gguf file.
 * @param n_gpu_layers  Layers to offload to the GPU; 0 = CPU only. Offloading is a model-load
 *                      decision in llama.cpp, not a session one, which is why it is here and
 *                      not in KoiSessionParams. Ignored by builds with no GPU backend.
 * @return handle, or NULL on failure. Caller owns; free with koi_model_free().
 */
KoiModel* koi_model_load(const char* path, int n_gpu_layers);

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

/* ── grammars ─────────────────────────────────────────────────────────────── */

/**
 * Convert a JSON schema to the GBNF grammar koi_generate() takes.
 *
 * The conversion lives here rather than in Kotlin because llama.cpp already ships it
 * (common/json-schema-to-grammar.cpp, ~1000 lines with a regex-to-grammar compiler in it) and
 * a second implementation would drift from the sampler that consumes its output.
 *
 * Appended at the end of this header on purpose: the JNI bridge numbering follows declaration
 * order, so inserting anywhere else renumbers every bridge after it.
 *
 * @param schema    JSON schema text.
 * @param out_buf   Destination buffer for the null-terminated grammar.
 * @param buf_size  Size of out_buf in bytes (including space for the null terminator).
 * @return          Bytes written (excluding null terminator), or -1 if the schema does not
 *                  parse, cannot be converted, or does not fit.
 */
int koi_json_schema_to_grammar(const char* schema, char* out_buf, int buf_size);

/* ── streaming generation ─────────────────────────────────────────────────── */

/**
 * Streaming, as a pull loop rather than a callback.
 *
 * Three calls instead of a function pointer because the JVM leg reaches this header through
 * generated JNI bridges, which marshal scalars and buffers and have no way to hand a C
 * callback back into the JVM. A pull loop needs neither: the caller drives it, and the same
 * three functions work identically through cinterop and through JNI.
 *
 * Deliberately no timing here. The caller decides when a token arrived, which is what lets one
 * clock in one place measure every engine the same way — a facade that timed itself would be
 * measuring something no other engine measures.
 *
 * Usage:
 *
 *     if (koi_generate_begin(s, sys, user, grammar) < 0) { ... }
 *     while ((n = koi_generate_next(s, buf, sizeof buf)) > 0) { ... }
 *     koi_generate_end(s);          // also required if the loop is abandoned early
 *
 * @return koi_generate_begin: prompt tokens on success, -1 on failure.
 *         koi_generate_next: bytes written (>0), 0 at end of generation, -1 on error.
 */
int koi_generate_begin(
    KoiSession*  session,
    const char*  system_prompt,
    const char*  user_prompt,
    const char*  grammar
);

int koi_generate_next(KoiSession* session, char* out_buf, int buf_size);

void koi_generate_end(KoiSession* session);

/* ── tokenizer ────────────────────────────────────────────────────────────── */

/**
 * Count the tokens in `text` using the model's own vocabulary.
 *
 * Counts `text` as content: no BOS, no chat template. A prompt that koi_generate wraps in a
 * template tokenizes to more than this, which is why koi_generate_begin reports the prompt's
 * real length separately.
 *
 * Appended at the end, like every addition here — the JNI bridge numbering follows declaration
 * order, so inserting above renumbers every bridge after it.
 *
 * @return the number of tokens, or -1 on invalid arguments.
 */
int koi_token_count(KoiSession* session, const char* text);

#ifdef __cplusplus
}
#endif
