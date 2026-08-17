#include "koinference_litertlm_facade.h"

#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <deque>
#include <mutex>
#include <map>
#include <string>

#include "engine.h"  // from the CLiteRTLM prebuilt

namespace {

// Owned by the facade and handed out as a const char*, so it has to outlive the
// call. Thread-local because two threads failing at once must not overwrite each
// other's message.
thread_local std::string g_last_error;

// The most recent reply, kept so that a caller whose buffer was too small can collect it
// without generating again. Thread-local for the same reason as the error.
thread_local std::string g_last_response;

/**
 * A streaming generation in flight.
 *
 * The runtime calls back from its own thread; the caller pulls from another. Everything shared
 * between them lives here behind one mutex. Keyed per conversation rather than thread-local
 * like the buffers above, because the producing thread is the runtime's, not the caller's.
 */
struct StreamState {
    std::mutex              mutex;
    std::condition_variable ready;
    std::deque<std::string> chunks;
    std::string             error;
    bool                    finished = false;
};

// One stream per conversation at a time, which matches the API: a conversation is a
// single-threaded conversation, and the Kotlin side holds a lock across a generation anyway.
std::mutex g_streams_mutex;
std::map<KoiLmConversation*, StreamState*> g_streams;

StreamState* find_stream(KoiLmConversation* conversation) {
    std::lock_guard<std::mutex> guard(g_streams_mutex);
    auto it = g_streams.find(conversation);
    return it == g_streams.end() ? nullptr : it->second;
}

// Runs on the runtime's thread. Does the minimum: copy the text, wake the puller.
void on_stream_chunk(void* user_data, const LiteRtLmStreamChunk* chunk) {
    auto* state = static_cast<StreamState*>(user_data);
    if (state == nullptr) return;

    const char* error = chunk ? litert_lm_stream_chunk_get_error(chunk) : nullptr;
    const char* text = chunk ? litert_lm_stream_chunk_get_text(chunk) : nullptr;
    const bool final_chunk = chunk == nullptr || litert_lm_stream_chunk_is_final(chunk);

    {
        std::lock_guard<std::mutex> guard(state->mutex);
        if (error != nullptr && error[0] != '\0') {
            state->error = error;
            state->finished = true;
        } else {
            if (text != nullptr && text[0] != '\0') state->chunks.emplace_back(text);
            if (final_chunk) state->finished = true;
        }
    }
    state->ready.notify_one();
}

void set_error(std::string message) { g_last_error = std::move(message); }
void clear_error() { g_last_error.clear(); }

// snprintf semantics: copy when it fits, report what was needed either way.
int copy_out(const std::string& value, char* out_buf, int buf_size) {
    if (value.size() + 1 <= static_cast<size_t>(buf_size)) {
        std::memcpy(out_buf, value.data(), value.size());
        out_buf[value.size()] = '\0';
    }
    return static_cast<int>(value.size());
}

// LiteRT-LM speaks JSON at its message boundary, and the facade has no JSON
// library linked in — it only ever *builds* one object shape, so a minimal
// escaper is enough. Parsing the reply is left to Kotlin.
void append_json_string(std::string& out, const char* value) {
    out += '"';
    for (const char* p = value; *p != '\0'; ++p) {
        const unsigned char c = static_cast<unsigned char>(*p);
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b";  break;
            case '\f': out += "\\f";  break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if (c < 0x20) {
                    char buf[7];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    // Valid UTF-8 passes through; JSON does not require escaping it.
                    out += static_cast<char>(c);
                }
        }
    }
    out += '"';
}

std::string message_json(const char* role, const char* content) {
    std::string json = "{\"role\":";
    append_json_string(json, role);
    json += ",\"content\":";
    append_json_string(json, content);
    json += '}';
    return json;
}

const char* backend_name(int backend) {
    return backend == KOILM_BACKEND_GPU ? "gpu" : "cpu";
}

}  // namespace

// The facade's handles are the upstream handles; the distinct typedefs exist so
// that the generated Kotlin bindings carry meaningful types rather than raw
// void*. reinterpret_cast between them is safe because neither is ever
// dereferenced on this side.
struct KoiLmEngine;
struct KoiLmConversation;

extern "C" {

const char* koilm_last_error(void) { return g_last_error.c_str(); }

KoiLmEngine* koilm_model_load(
    const char* path,
    const char* cache_dir,
    int         backend,
    int         n_threads,
    int         max_tokens
) {
    clear_error();

    if (path == nullptr) {
        set_error("model path is null");
        return nullptr;
    }

    LiteRtLmEngineSettings* settings = litert_lm_engine_settings_create(
        path, backend_name(backend), /*vision_backend_str=*/nullptr,
        /*audio_backend_str=*/nullptr);
    if (settings == nullptr) {
        set_error(std::string("could not create engine settings for ") + path);
        return nullptr;
    }

    if (cache_dir != nullptr && cache_dir[0] != '\0') {
        litert_lm_engine_settings_set_cache_dir(settings, cache_dir);
    }
    if (n_threads > 0) {
        litert_lm_engine_settings_set_num_threads(settings, n_threads);
    }
    if (max_tokens > 0) {
        litert_lm_engine_settings_set_max_num_tokens(settings, max_tokens);
    }

    LiteRtLmEngine* engine = litert_lm_engine_create(settings);
    litert_lm_engine_settings_delete(settings);

    if (engine == nullptr) {
        set_error(std::string("could not create engine for ") + path +
                  " (unsupported format, or the file is not a .litertlm/.task)");
        return nullptr;
    }
    return reinterpret_cast<KoiLmEngine*>(engine);
}

void koilm_model_free(KoiLmEngine* engine) {
    if (engine != nullptr) {
        litert_lm_engine_delete(reinterpret_cast<LiteRtLmEngine*>(engine));
    }
}

KoiLmSessionParams koilm_default_session_params(void) {
    KoiLmSessionParams params;
    params.max_tokens = 0;
    params.top_k = 40;
    params.top_p = 0.95f;
    params.temp = 0.8f;
    params.seed = -1;
    params.greedy = 0;
    return params;
}

KoiLmConversation* koilm_session_create(
    KoiLmEngine*       engine,
    KoiLmSessionParams params,
    const char*        system_prompt
) {
    clear_error();

    if (engine == nullptr) {
        set_error("engine is null");
        return nullptr;
    }

    // Always the top-p sampler, even for greedy.
    //
    // The runtime declares kLiteRtLmSamplerTypeGreedy, but a conversation created with it fails
    // every send_message in this prebuilt (v0.15.0), on both models tested. Top-k of 1 through
    // the ordinary sampler is argmax by another name, it is verified to work, and it is what the
    // Android leg has to use anyway — its public SamplerConfig exposes no sampler type. Both
    // legs spelling greedy the same way is worth more than using the type.
    LiteRtLmSamplerParams* sampler =
        litert_lm_sampler_params_create(kLiteRtLmSamplerTypeTopP);
    if (sampler == nullptr) {
        set_error("could not create sampler params");
        return nullptr;
    }

    const int   top_k = (params.greedy != 0) ? 1 : params.top_k;
    // Temperature is irrelevant once there is a single candidate, and 0 is not "no randomness"
    // to this sampler — it keeps sampling — so greedy passes a neutral value rather than the
    // caller's 0.
    const float temp  = (params.greedy != 0) ? 1.0f : params.temp;

    if (top_k > 0) litert_lm_sampler_params_set_top_k(sampler, top_k);
    if (params.top_p > 0.0f) litert_lm_sampler_params_set_top_p(sampler, params.top_p);
    litert_lm_sampler_params_set_temperature(sampler, temp);
    // Only when asked for: an unconditional seed would make every conversation on this leg
    // reproducible while the Android leg's default is not, for callers who never set one.
    if (params.seed >= 0) litert_lm_sampler_params_set_seed(sampler, params.seed);

    LiteRtLmSessionConfig* session_config = litert_lm_session_config_create();
    if (session_config == nullptr) {
        litert_lm_sampler_params_delete(sampler);
        set_error("could not create session config");
        return nullptr;
    }
    litert_lm_session_config_set_sampler_params(session_config, sampler);
    if (params.max_tokens > 0) {
        litert_lm_session_config_set_max_output_tokens(session_config, params.max_tokens);
    }

    LiteRtLmConversationConfig* config = litert_lm_conversation_config_create();
    if (config == nullptr) {
        litert_lm_session_config_delete(session_config);
        litert_lm_sampler_params_delete(sampler);
        set_error("could not create conversation config");
        return nullptr;
    }
    litert_lm_conversation_config_set_session_config(config, session_config);

    if (system_prompt != nullptr && system_prompt[0] != '\0') {
        const std::string system = message_json("system", system_prompt);
        litert_lm_conversation_config_set_system_message(config, system.c_str());
    }

    // Constrained decoding has to be armed on the config; the schema itself is
    // supplied per message, so this is on whether or not a schema ever arrives.
    litert_lm_conversation_config_set_enable_constrained_decoding(config, true);
    const LiteRtLmConstraintProviderType provider =
        kLiteRtLmConstraintProviderTypeLlGuidance;
    litert_lm_conversation_config_set_constraint_provider(config, &provider);

    LiteRtLmConversation* conversation = litert_lm_conversation_create(
        reinterpret_cast<LiteRtLmEngine*>(engine), config);

    litert_lm_conversation_config_delete(config);
    litert_lm_session_config_delete(session_config);
    litert_lm_sampler_params_delete(sampler);

    if (conversation == nullptr) {
        set_error("could not create conversation");
        return nullptr;
    }
    return reinterpret_cast<KoiLmConversation*>(conversation);
}

void koilm_session_free(KoiLmConversation* conversation) {
    if (conversation != nullptr) {
        litert_lm_conversation_delete(reinterpret_cast<LiteRtLmConversation*>(conversation));
    }
}

int koilm_generate(
    KoiLmConversation* conversation,
    const char*        user_prompt,
    const char*        json_schema,
    char*              out_buf,
    int                buf_size
) {
    clear_error();

    if (conversation == nullptr || user_prompt == nullptr || out_buf == nullptr ||
        buf_size <= 0) {
        set_error("invalid arguments to koilm_generate");
        return -1;
    }

    LiteRtLmConversationOptionalArgs* args = nullptr;
    if (json_schema != nullptr && json_schema[0] != '\0') {
        args = litert_lm_conversation_optional_args_create();
        if (args == nullptr) {
            set_error("could not create optional args for the schema constraint");
            return -1;
        }
        litert_lm_conversation_optional_args_set_constraint(
            args, kLiteRtLmConstraintTypeJsonSchema, json_schema);
    }

    const std::string message = message_json("user", user_prompt);
    LiteRtLmJsonResponse* response = litert_lm_conversation_send_message(
        reinterpret_cast<LiteRtLmConversation*>(conversation), message.c_str(),
        /*extra_context=*/nullptr, args);

    if (args != nullptr) litert_lm_conversation_optional_args_delete(args);

    if (response == nullptr) {
        set_error("send_message failed");
        return -1;
    }

    const char* text = litert_lm_json_response_get_string(response);
    if (text == nullptr) {
        litert_lm_json_response_delete(response);
        set_error("response carried no string");
        return -1;
    }

    // Kept before any size check. A reply that does not fit must not be thrown away: asking
    // for it again would mean a second send_message, which is another user turn in the
    // conversation's history and another full generation.
    g_last_response.assign(text);
    litert_lm_json_response_delete(response);

    return copy_out(g_last_response, out_buf, buf_size);
}

int koilm_last_response(char* out_buf, int buf_size) {
    if (out_buf == nullptr || buf_size <= 0) {
        set_error("invalid arguments to koilm_last_response");
        return -1;
    }
    return copy_out(g_last_response, out_buf, buf_size);
}

}  // extern "C"

/* ── streaming generation ─────────────────────────────────────────────────── */

int koilm_stream_begin(
    KoiLmConversation* conversation,
    const char*        user_prompt,
    const char*        json_schema
) {
    clear_error();

    if (conversation == nullptr || user_prompt == nullptr) {
        set_error("invalid arguments to koilm_stream_begin");
        return -1;
    }

    // Any stream left open is discarded first: callers abandon loops, and a stale state would
    // otherwise receive callbacks belonging to the next generation.
    koilm_stream_end(conversation);

    LiteRtLmConversationOptionalArgs* args = nullptr;
    if (json_schema != nullptr && json_schema[0] != '\0') {
        args = litert_lm_conversation_optional_args_create();
        if (args == nullptr) {
            set_error("could not create optional args for the schema constraint");
            return -1;
        }
        litert_lm_conversation_optional_args_set_constraint(
            args, kLiteRtLmConstraintTypeJsonSchema, json_schema);
    }

    auto* state = new StreamState();
    {
        std::lock_guard<std::mutex> guard(g_streams_mutex);
        g_streams[conversation] = state;
    }

    const std::string message = message_json("user", user_prompt);
    const int rc = litert_lm_conversation_send_message_stream(
        reinterpret_cast<LiteRtLmConversation*>(conversation), message.c_str(),
        /*extra_context=*/nullptr, args, &on_stream_chunk, state);

    if (args != nullptr) litert_lm_conversation_optional_args_delete(args);

    if (rc != 0) {
        koilm_stream_end(conversation);
        set_error("send_message_stream failed to start");
        return -1;
    }
    return 0;
}

int koilm_stream_next(KoiLmConversation* conversation, char* out_buf, int buf_size) {
    if (conversation == nullptr || out_buf == nullptr || buf_size <= 0) {
        set_error("invalid arguments to koilm_stream_next");
        return -1;
    }

    StreamState* state = find_stream(conversation);
    if (state == nullptr) return 0;  // nothing in flight; treated as end of stream

    std::string chunk;
    {
        std::unique_lock<std::mutex> lock(state->mutex);
        // Waits rather than spins: the runtime decides when the next token exists, and a busy
        // loop here would compete with the thread producing it.
        state->ready.wait(lock, [state] {
            return !state->chunks.empty() || state->finished;
        });

        if (!state->error.empty()) {
            set_error(state->error);
            return -1;
        }
        if (state->chunks.empty()) return 0;  // finished, and drained

        chunk = std::move(state->chunks.front());
        state->chunks.pop_front();
    }

    // A chunk that does not fit is an error rather than a truncation: half a UTF-8 sequence
    // would corrupt the reply silently.
    if (static_cast<int>(chunk.size()) >= buf_size) {
        set_error("chunk larger than the caller's buffer");
        return -1;
    }
    std::memcpy(out_buf, chunk.data(), chunk.size());
    out_buf[chunk.size()] = '\0';
    return static_cast<int>(chunk.size());
}

void koilm_stream_end(KoiLmConversation* conversation) {
    if (conversation == nullptr) return;

    StreamState* state = nullptr;
    {
        std::lock_guard<std::mutex> guard(g_streams_mutex);
        auto it = g_streams.find(conversation);
        if (it == g_streams.end()) return;
        state = it->second;
        g_streams.erase(it);
    }

    // The runtime may still be mid-callback. Marking finished under the lock and waiting for
    // the final chunk keeps the callback from writing into freed memory; the conversation's own
    // cancel is what actually stops the generation.
    litert_lm_conversation_cancel_process(reinterpret_cast<LiteRtLmConversation*>(conversation));
    {
        std::unique_lock<std::mutex> lock(state->mutex);
        state->ready.wait(lock, [state] { return state->finished; });
    }
    delete state;
}
