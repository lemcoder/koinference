#include "koinference_facade.h"

#include "llama.h"
#include "common.h"
#include "chat.h"
#include "sampling.h"
#include "json-schema-to-grammar.h"
// json-schema-to-grammar.h only forward-declares the json type since b10472; parsing needs the
// definition.
#include <nlohmann/json.hpp>

#include <algorithm>
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
 * State of a streaming generation, between koi_generate_begin() and koi_generate_end().
 *
 * The sampler is per-generation because the grammar can change per call, and the position has
 * to survive across koi_generate_next() calls — that is the whole difference between a pull
 * loop and the blocking call.
 */
struct KoiGeneration {
    common_sampler* sampler   = nullptr;
    llama_pos       pos       = 0;
    int             produced  = 0;
    int             n_predict = 0;
    bool            finished  = true;
};

struct KoiSession {
    llama_model*              model;   // non-owning; lifetime tied to KoiModel
    llama_context*            ctx;
    llama_batch               batch;
    common_chat_templates_ptr chat_templates;
    KoiSessionParams          params;
    KoiGeneration             generation;
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
    koi_generate_end(session);
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

// Formats the prompt the way this model's chat template asks for, or plainly when it has none.
static std::string koi_build_prompt(
        KoiSession* session,
        const char* system_prompt,
        const char* user_prompt,
        bool        has_template
) {
    std::string prompt;
    if (has_template) {
        std::vector<common_chat_msg> msgs;
        if (system_prompt && system_prompt[0] != '\0') {
            common_chat_msg sys{ "system", system_prompt };
            prompt = common_chat_format_single(session->chat_templates.get(), msgs, sys, false, false);
            msgs.push_back(std::move(sys));
        }
        common_chat_msg usr{ "user", user_prompt };
        prompt += common_chat_format_single(session->chat_templates.get(), msgs, usr, true, false);
    } else {
        if (system_prompt && system_prompt[0] != '\0') {
            prompt = std::string(system_prompt) + "\n";
        }
        prompt += user_prompt;
    }
    return prompt;
}

int koi_generate_begin(
        KoiSession*  session,
        const char*  system_prompt,
        const char*  user_prompt,
        const char*  grammar
) {
    if (!session || !user_prompt) return -1;

    // Any generation left open is discarded rather than leaked: callers get this wrong, and a
    // stranded sampler would otherwise outlive the session.
    koi_generate_end(session);

    // llama_kv_cache_clear was removed after b5001; the memory abstraction replaced it.
    llama_memory_clear(llama_get_memory(session->ctx), /*data=*/true);

    const bool has_template = common_chat_templates_was_explicit(session->chat_templates.get());
    const std::string prompt = koi_build_prompt(session, system_prompt, user_prompt, has_template);

    const auto tokens = common_tokenize(session->ctx, prompt, has_template, has_template);
    if (decode_in_batches(session->ctx, session->batch, tokens, 0, true) != 0) return -1;

    common_params_sampling sparams;
    sparams.temp  = session->params.temp;
    sparams.top_k = session->params.top_k;
    sparams.min_p = session->params.min_p;
    if (grammar && grammar[0] != '\0') {
        // common_params_sampling::grammar became a tagged struct: a bare string no longer
        // assigns, and the tag is what tells the sampler this is user-provided GBNF rather
        // than one of the built-in variants.
        sparams.grammar = common_grammar(COMMON_GRAMMAR_TYPE_USER, grammar);
    }

    common_sampler* sampler = common_sampler_init(session->model, sparams);
    if (!sampler) return -1;

    session->generation.sampler   = sampler;
    session->generation.pos       = static_cast<llama_pos>(tokens.size());
    session->generation.produced  = 0;
    session->generation.n_predict = (session->params.n_predict <= 0)
        ? DEFAULT_N_PREDICT
        : session->params.n_predict;
    session->generation.finished  = false;

    return static_cast<int>(tokens.size());
}

int koi_generate_next(KoiSession* session, char* out_buf, int buf_size) {
    if (!session || !out_buf || buf_size <= 0) return -1;

    KoiGeneration& gen = session->generation;
    if (gen.finished || !gen.sampler) return 0;
    if (gen.produced >= gen.n_predict) {
        gen.finished = true;
        return 0;
    }

    const llama_token id = common_sampler_sample(gen.sampler, session->ctx, -1);
    common_sampler_accept(gen.sampler, id, true);

    const llama_vocab* vocab = llama_model_get_vocab(session->model);
    if (llama_vocab_is_eog(vocab, id)) {
        gen.finished = true;
        return 0;
    }

    const std::string piece = common_token_to_piece(session->ctx, id);
    // A piece that does not fit is an error rather than a truncation: half a UTF-8 sequence
    // handed to the caller would corrupt the reply silently.
    if (static_cast<int>(piece.size()) >= buf_size) return -1;
    std::memcpy(out_buf, piece.c_str(), piece.size());
    out_buf[piece.size()] = '\0';
    gen.produced++;

    // Decoding the token just emitted is what prepares the next one, so it happens here rather
    // than at the top: the caller gets its chunk as early as possible.
    common_batch_clear(session->batch);
    common_batch_add(session->batch, id, gen.pos++, {0}, true);
    if (llama_decode(session->ctx, session->batch) != 0) {
        gen.finished = true;
        return -1;
    }

    return static_cast<int>(piece.size());
}

void koi_generate_end(KoiSession* session) {
    if (!session) return;
    if (session->generation.sampler) {
        common_sampler_free(session->generation.sampler);
    }
    session->generation = KoiGeneration{};
}

int koi_generate(
        KoiSession*  session,
        const char*  system_prompt,
        const char*  user_prompt,
        const char*  grammar,
        char*        out_buf,
        int          buf_size
) {
    if (!session || !user_prompt || !out_buf || buf_size <= 0) return -1;

    // The blocking call is the streaming one, drained. One decode implementation, so the two
    // cannot drift into producing different text.
    if (koi_generate_begin(session, system_prompt, user_prompt, grammar) < 0) return -1;

    std::string result;
    char piece[512];
    while (true) {
        const int written = koi_generate_next(session, piece, sizeof(piece));
        if (written < 0) {
            koi_generate_end(session);
            return -1;
        }
        if (written == 0) break;
        result.append(piece, static_cast<size_t>(written));
    }
    koi_generate_end(session);

    const int len = std::min(static_cast<int>(result.size()), buf_size - 1);
    std::memcpy(out_buf, result.c_str(), len);
    out_buf[len] = '\0';
    return len;
}

/* ── embeddings ───────────────────────────────────────────────────────────── */

int koi_embed(KoiSession* session, const char* text, float* out_buf, int buf_size) {
    if (!session || !text || !out_buf || buf_size <= 0) return -1;

    llama_memory_clear(llama_get_memory(session->ctx), /*data=*/true);

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

