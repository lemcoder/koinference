#include "koinference_facade.h"

#include "llama.h"
#include "common.h"
#include "chat.h"
#include "sampling.h"

#include <algorithm>
#include <cstring>
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

struct KoiSession {
    llama_model*              model;   // non-owning; lifetime tied to KoiModel
    llama_context*            ctx;
    llama_batch               batch;
    common_chat_templates_ptr chat_templates;
    KoiSessionParams          params;
};

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

KoiModel* koi_model_load(const char* path) {
    if (!path) return nullptr;
    llama_model_params params = llama_model_default_params();
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

    llama_memory_clear(llama_get_memory(session->ctx), false);

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

    for (int i = 0; i < n_predict; i++) {
        const llama_token id = common_sampler_sample(sampler, session->ctx, -1);
        common_sampler_accept(sampler, id, true);

        if (llama_vocab_is_eog(vocab, id)) break;

        result += common_token_to_piece(session->ctx, id);

        common_batch_clear(session->batch);
        common_batch_add(session->batch, id, pos++, {0}, true);
        if (llama_decode(session->ctx, session->batch) != 0) {
            common_sampler_free(sampler);
            return -1;
        }
    }

    common_sampler_free(sampler);

    const int len = std::min(static_cast<int>(result.size()), buf_size - 1);
    std::memcpy(out_buf, result.c_str(), len);
    out_buf[len] = '\0';
    return len;
}

/* ── embeddings ───────────────────────────────────────────────────────────── */

int koi_embed(KoiSession* session, const char* text, float* out_buf, int buf_size) {
    if (!session || !text || !out_buf || buf_size <= 0) return -1;

    llama_memory_clear(llama_get_memory(session->ctx), false);

    const auto tokens = common_tokenize(session->ctx, text, true, true);
    if (decode_in_batches(session->ctx, session->batch, tokens, 0, false) != 0) return -1;

    const int n_embd = llama_model_n_embd(session->model);
    if (n_embd > buf_size) return -1;

    const float* embd = llama_get_embeddings(session->ctx);
    if (!embd) return -1;

    std::memcpy(out_buf, embd, static_cast<size_t>(n_embd) * sizeof(float));
    return n_embd;
}
