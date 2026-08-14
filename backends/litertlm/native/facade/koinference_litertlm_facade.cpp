#include "koinference_litertlm_facade.h"

#include <cstdio>
#include <cstring>
#include <string>

#include "engine.h"  // from the CLiteRTLM prebuilt

namespace {

// Owned by the facade and handed out as a const char*, so it has to outlive the
// call. Thread-local because two threads failing at once must not overwrite each
// other's message.
thread_local std::string g_last_error;

void set_error(std::string message) { g_last_error = std::move(message); }
void clear_error() { g_last_error.clear(); }

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

    LiteRtLmSamplerParams* sampler =
        litert_lm_sampler_params_create(kLiteRtLmSamplerTypeTopP);
    if (sampler == nullptr) {
        set_error("could not create sampler params");
        return nullptr;
    }
    if (params.top_k > 0) litert_lm_sampler_params_set_top_k(sampler, params.top_k);
    if (params.top_p > 0.0f) litert_lm_sampler_params_set_top_p(sampler, params.top_p);
    litert_lm_sampler_params_set_temperature(sampler, params.temp);

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

    const size_t length = std::strlen(text);
    if (length + 1 > static_cast<size_t>(buf_size)) {
        litert_lm_json_response_delete(response);
        set_error("response needs " + std::to_string(length + 1) +
                  " bytes but the buffer holds " + std::to_string(buf_size));
        return -1;
    }

    std::memcpy(out_buf, text, length);
    out_buf[length] = '\0';
    litert_lm_json_response_delete(response);
    return static_cast<int>(length);
}

}  // extern "C"
