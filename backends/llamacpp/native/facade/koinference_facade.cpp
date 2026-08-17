#include "koinference_facade.h"

#include "llama.h"
#include "common.h"
#include "chat.h"
#include "sampling.h"
#include "json-schema-to-grammar.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <exception>
#include <string>
#include <thread>
#include <vector>

static constexpr int   DEFAULT_N_CTX     = 4096;
static constexpr int   DEFAULT_N_PREDICT = 512;
static constexpr float DEFAULT_TEMP      = 0.8f;
static constexpr int   DEFAULT_TOP_K     = 40;
static constexpr float DEFAULT_MIN_P     = 0.05f;
static constexpr int   BATCH_SIZE        = 512;

struct KoiModel {
    llama_model* model;
};

/**
 * Timings of the last koi_generate() on a session.
 *
 * Recorded where the truth is — inside the decode loop — because the alternative is the caller
 * dividing a total by a token count, which is not what time-to-first-token means.
 */
struct KoiGenerationStats {
    bool      valid         = false;
    int       prompt_tokens = 0;
    int       decode_tokens = 0;
    long long prefill_us    = 0;  // tokenize + prompt decode
    long long ttft_us       = 0;  // call entry → first sampled token
    long long decode_us     = 0;  // first sampled token → last
};

struct KoiSession {
    llama_model*              model;   // non-owning; lifetime tied to KoiModel
    llama_context*            ctx;
    llama_batch               batch;
    common_chat_templates_ptr chat_templates;
    KoiSessionParams          params;
    KoiGenerationStats        stats;
};

using koi_clock = std::chrono::steady_clock;

static long long koi_us_since(const koi_clock::time_point& start) {
    return std::chrono::duration_cast<std::chrono::microseconds>(koi_clock::now() - start).count();
}

/* ── backend ──────────────────────────────────────────────────────────────── */

void koi_backend_init(void) {
    llama_backend_init();
}

void koi_backend_free(void) {
    llama_backend_free();
}

const char* koi_system_info(void) {
    return llama_print_system_info();
}

/* ── model ────────────────────────────────────────────────────────────────── */

KoiModel* koi_model_load(const char* path, int n_gpu_layers) {
    if (!path) return nullptr;
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = (n_gpu_layers < 0) ? 0 : n_gpu_layers;
    llama_model* m = llama_model_load_from_file(path, params);
    if (!m) return nullptr;
    auto* handle = new KoiModel;
    handle->model = m;
    return handle;
}

void koi_model_free(KoiModel* model) {
    if (!model) return;
    llama_model_free(model->model);
    delete model;
}

/* ── session ──────────────────────────────────────────────────────────────── */

KoiSessionParams koi_default_session_params(void) {
    const int hw_threads = static_cast<int>(std::thread::hardware_concurrency());
    const int auto_threads = std::max(1, hw_threads - 2);
    return {DEFAULT_N_CTX, auto_threads, DEFAULT_N_PREDICT, DEFAULT_TEMP, DEFAULT_TOP_K, DEFAULT_MIN_P};
}

KoiSession* koi_session_create(KoiModel* model, KoiSessionParams params) {
    if (!model) return nullptr;

    const int n_threads = (params.n_threads <= 0)
        ? std::max(1, static_cast<int>(std::thread::hardware_concurrency()) - 2)
        : params.n_threads;

    const int trained_ctx = llama_model_n_ctx_train(model->model);

    llama_context_params ctx_params    = llama_context_default_params();
    ctx_params.n_ctx                   = (params.n_ctx <= 0) ? trained_ctx : std::min(params.n_ctx, trained_ctx);
    ctx_params.n_batch                 = BATCH_SIZE;
    ctx_params.n_ubatch                = BATCH_SIZE;
    ctx_params.n_threads               = n_threads;
    ctx_params.n_threads_batch         = n_threads;

    llama_context* ctx = llama_init_from_model(model->model, ctx_params);
    if (!ctx) return nullptr;

    auto* session            = new KoiSession;
    session->model           = model->model;
    session->ctx             = ctx;
    session->batch           = llama_batch_init(BATCH_SIZE, 0, 1);
    session->chat_templates  = common_chat_templates_init(model->model, "");
    session->params          = params;
    return session;
}

void koi_session_free(KoiSession* session) {
    if (!session) return;
    session->chat_templates.reset();
    llama_batch_free(session->batch);
    llama_free(session->ctx);
    delete session;
}

/* ── generation helpers ───────────────────────────────────────────────────── */

static int decode_in_batches(
        llama_context* ctx,
        llama_batch&   batch,
        const llama_tokens& tokens,
        llama_pos      start_pos,
        bool           compute_last_logit = false
) {
    for (int i = 0; i < static_cast<int>(tokens.size()); i += BATCH_SIZE) {
        const int cur = std::min(static_cast<int>(tokens.size()) - i, BATCH_SIZE);
        common_batch_clear(batch);
        for (int j = 0; j < cur; j++) {
            const bool want_logit = compute_last_logit && (i + j == static_cast<int>(tokens.size()) - 1);
            common_batch_add(batch, tokens[i + j], start_pos + i + j, {0}, want_logit);
        }
        if (llama_decode(ctx, batch) != 0) return -1;
    }
    return 0;
}

/* ── generation ───────────────────────────────────────────────────────────── */

int koi_generate(
        KoiSession*  session,
        const char*  system_prompt,
        const char*  user_prompt,
        const char*  grammar,
        char*        out_buf,
        int          buf_size
) {
    if (!session || !user_prompt || !out_buf || buf_size <= 0) return -1;

    // Invalidated up front: a call that fails half way must not leave the previous call's
    // numbers readable as if they described this one.
    session->stats = KoiGenerationStats{};
    const auto t_start = koi_clock::now();

    llama_kv_cache_clear(session->ctx);

    // Build prompt string
    const bool has_template = common_chat_templates_was_explicit(session->chat_templates.get());
    std::string prompt;
    std::vector<common_chat_msg> msgs;

    if (has_template) {
        if (system_prompt && system_prompt[0] != '\0') {
            common_chat_msg sys{ "system", system_prompt };
            prompt = common_chat_format_single(session->chat_templates.get(), msgs, sys, false, false);
            msgs.push_back(std::move(sys));
        }
        common_chat_msg usr{ "user", user_prompt };
        prompt += common_chat_format_single(session->chat_templates.get(), msgs, usr, true, false);
        msgs.push_back(std::move(usr));
    } else {
        if (system_prompt && system_prompt[0] != '\0') {
            prompt = std::string(system_prompt) + "\n";
        }
        prompt += user_prompt;
    }

    // Tokenize and prefill
    const auto tokens = common_tokenize(session->ctx, prompt, has_template, has_template);
    if (decode_in_batches(session->ctx, session->batch, tokens, 0, true) != 0) return -1;
    const long long prefill_us = koi_us_since(t_start);

    // Build sampler (per-call so grammar can vary)
    common_params_sampling sparams;
    sparams.temp  = session->params.temp;
    sparams.top_k = session->params.top_k;
    sparams.min_p = session->params.min_p;
    if (grammar && grammar[0] != '\0') {
        sparams.grammar = grammar;
    }

    auto* sampler = common_sampler_init(session->model, sparams);
    if (!sampler) return -1;

    // Decode
    std::string result;
    llama_pos pos = static_cast<llama_pos>(tokens.size());
    const int n_predict = (session->params.n_predict <= 0) ? DEFAULT_N_PREDICT : session->params.n_predict;
    const llama_vocab* vocab = llama_model_get_vocab(session->model);

    long long ttft_us       = 0;
    int       decode_tokens = 0;
    auto      t_first_token = koi_clock::now();

    for (int i = 0; i < n_predict; i++) {
        const llama_token id = common_sampler_sample(sampler, session->ctx, -1);
        common_sampler_accept(sampler, id, true);

        if (i == 0) {
            // Stamped before the end-of-generation check: the first token exists by now, and a
            // model that immediately emits EOG still took this long to decide that.
            t_first_token = koi_clock::now();
            ttft_us = std::chrono::duration_cast<std::chrono::microseconds>(
                t_first_token - t_start).count();
        }

        if (llama_vocab_is_eog(vocab, id)) break;

        result += common_token_to_piece(session->ctx, id);
        decode_tokens++;

        common_batch_clear(session->batch);
        common_batch_add(session->batch, id, pos++, {0}, true);
        if (llama_decode(session->ctx, session->batch) != 0) {
            common_sampler_free(sampler);
            return -1;
        }
    }

    common_sampler_free(sampler);

    session->stats.valid         = true;
    session->stats.prompt_tokens = static_cast<int>(tokens.size());
    session->stats.decode_tokens = decode_tokens;
    session->stats.prefill_us    = prefill_us;
    session->stats.ttft_us       = ttft_us;
    // From the first token, not from call entry: prefill is reported separately and adding it
    // here would make decode tokens/sec a function of prompt length.
    session->stats.decode_us     = std::chrono::duration_cast<std::chrono::microseconds>(
        koi_clock::now() - t_first_token).count();

    const int len = std::min(static_cast<int>(result.size()), buf_size - 1);
    std::memcpy(out_buf, result.c_str(), len);
    out_buf[len] = '\0';
    return len;
}

/* ── embeddings ───────────────────────────────────────────────────────────── */

int koi_embed(KoiSession* session, const char* text, float* out_buf, int buf_size) {
    if (!session || !text || !out_buf || buf_size <= 0) return -1;

    llama_kv_cache_clear(session->ctx);

    const auto tokens = common_tokenize(session->ctx, text, true, true);
    if (decode_in_batches(session->ctx, session->batch, tokens, 0, false) != 0) return -1;

    const int n_embd = llama_model_n_embd(session->model);
    if (n_embd > buf_size) return -1;

    const float* embd = llama_get_embeddings(session->ctx);
    if (!embd) return -1;

    std::memcpy(out_buf, embd, static_cast<size_t>(n_embd) * sizeof(float));
    return n_embd;
}

/* ── grammars ─────────────────────────────────────────────────────────────── */

int koi_json_schema_to_grammar(const char* schema, char* out_buf, int buf_size) {
    if (!schema || !out_buf || buf_size <= 0) return -1;

    // Both the parse and the conversion throw on malformed input — an invalid schema is a
    // caller error, and letting it cross the extern "C" boundary is undefined behaviour.
    std::string grammar;
    try {
        grammar = json_schema_to_grammar(nlohmann::ordered_json::parse(schema));
    } catch (const std::exception&) {
        return -1;
    }

    // Truncating a grammar produces one that parses but constrains something else, so a
    // short buffer is an error rather than a partial write.
    if (static_cast<int>(grammar.size()) >= buf_size) return -1;

    std::memcpy(out_buf, grammar.c_str(), grammar.size());
    out_buf[grammar.size()] = '\0';
    return static_cast<int>(grammar.size());
}

/* ── generation telemetry ─────────────────────────────────────────────────── */

// -1 rather than 0 for "no measurement": a caller must be able to tell an unmeasured session
// from one that genuinely produced no tokens.
static int koi_stat(KoiSession* session, long long KoiGenerationStats::* field) {
    if (!session || !session->stats.valid) return -1;
    return static_cast<int>(session->stats.*field);
}

int koi_last_prompt_tokens(KoiSession* session) {
    if (!session || !session->stats.valid) return -1;
    return session->stats.prompt_tokens;
}

int koi_last_decode_tokens(KoiSession* session) {
    if (!session || !session->stats.valid) return -1;
    return session->stats.decode_tokens;
}

int koi_last_prefill_us(KoiSession* session) { return koi_stat(session, &KoiGenerationStats::prefill_us); }
int koi_last_ttft_us(KoiSession* session)    { return koi_stat(session, &KoiGenerationStats::ttft_us); }
int koi_last_decode_us(KoiSession* session)  { return koi_stat(session, &KoiGenerationStats::decode_us); }
