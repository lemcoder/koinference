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
 * Plain 4-byte fields, no padding.
 */
typedef struct {
    int   max_tokens;  /**< Max tokens to generate; <= 0 = model default. */
    int   top_k;       /**< Top-k sampling; 0 = disabled. */
    float top_p;       /**< Top-p sampling; 0.0 = disabled. */
    float temp;        /**< Sampling temperature. */
    int   seed;        /**< Sampler seed; < 0 = leave the runtime's own seeding. */
    /**
     * Non-zero takes the most likely token every step, ignoring top_k, top_p and temp.
     *
     * A field rather than an inference from temp, because temperature 0 does *not* mean greedy
     * to this runtime: its sampler still samples and answers the same question differently on
     * consecutive calls.
     */
    int   greedy;
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
 * @return            The reply's length in bytes, excluding the null terminator,
 *                    or -1 on error.
 *
 *                    snprintf's contract: the return value is what the reply
 *                    needs, not what was written. A value >= buf_size means
 *                    nothing was written; collect the reply with
 *                    koilm_last_response() and a buffer of return + 1 bytes.
 *                    Sizing the buffer up front is impossible — the length is a
 *                    property of what the model produced.
 */
int koilm_generate(
    KoiLmConversation* conversation,
    const char*        user_prompt,
    const char*        json_schema,
    char*              out_buf,
    int                buf_size
);

/**
 * Copy the most recent reply on this thread into out_buf.
 *
 * For the case where koilm_generate() reported a size larger than the buffer it
 * was given. Generating again is not the way out of that: it would send a second
 * user message, adding a turn to the conversation and paying for the tokens
 * twice. The reply is retained until the next koilm_generate() on this thread.
 *
 * @return same contract as koilm_generate(), or -1 on invalid arguments.
 */
int koilm_last_response(char* out_buf, int buf_size);

/* ── streaming generation ─────────────────────────────────────────────────── */

/**
 * Streaming as a pull loop, matching the llama.cpp facade's shape.
 *
 * LiteRT-LM's own streaming is push: it returns immediately and calls back from a background
 * thread. That thread belongs to the runtime, so handing it straight to Kotlin would mean
 * Kotlin code running on a thread it does not own, on both bindings, for no benefit. Instead
 * the facade buffers chunks and the caller pulls them, which is the same loop the llama.cpp
 * facade offers — one Kotlin implementation drives both.
 *
 * No timing here either: the caller decides when a chunk arrived.
 *
 *     if (koilm_stream_begin(c, prompt, schema) != 0) { ... }
 *     while ((n = koilm_stream_next(c, buf, sizeof buf)) > 0) { ... }
 *     koilm_stream_end(c);          // also required if the loop is abandoned early
 *
 * @return koilm_stream_begin: 0 on success, -1 on failure (see koilm_last_error()).
 *         koilm_stream_next: bytes written (>0), 0 when the reply is complete, -1 on error.
 *                            Blocks until a chunk is available.
 */
int koilm_stream_begin(
    KoiLmConversation* conversation,
    const char*        user_prompt,
    const char*        json_schema
);

int koilm_stream_next(KoiLmConversation* conversation, char* out_buf, int buf_size);

void koilm_stream_end(KoiLmConversation* conversation);

/* ── tokenizer ────────────────────────────────────────────────────────────── */

/**
 * Count the tokens in `text` using the engine's own tokenizer.
 *
 * The count, not the ids: a benchmark needs to divide by a number, and returning the ids would
 * mean a second call to fetch them and a buffer to size. Ids can be added later without
 * disturbing this, since new functions are appended.
 *
 * Appended at the end of the header, like every other addition here — the generated JNI bridge
 * numbering follows declaration order, so inserting above renumbers every bridge after it.
 *
 * @return the number of tokens, or -1 on failure (see koilm_last_error()).
 */
int koilm_token_count(KoiLmEngine* engine, const char* text);

#ifdef __cplusplus
}
#endif
