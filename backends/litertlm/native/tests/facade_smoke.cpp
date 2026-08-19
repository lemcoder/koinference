// Links the facade against the prebuilt CLiteRTLM runtime and calls into it.
// Without a model this cannot generate, but it does prove the seam: that every
// litert_lm_* symbol the facade references resolves at link time, and that a
// call actually reaches the runtime and comes back.
//
// Point KOI_TEST_MODEL at a .litertlm file to exercise generation as well.

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#include "koinference_litertlm_facade.h"

namespace {

int failures = 0;

void check(bool condition, const char* what) {
    std::printf("%s %s\n", condition ? "  ok  " : "  FAIL", what);
    if (!condition) ++failures;
}

}  // namespace

int main() {
    std::printf("koinference LiteRT-LM facade smoke test\n");

    check(koilm_last_error() != nullptr, "koilm_last_error() is never null");
    check(koilm_last_error()[0] == '\0', "no error before anything has failed");

    const KoiLmSessionParams defaults = koilm_default_session_params();
    check(defaults.top_k > 0, "default params carry a top_k");
    check(defaults.temp > 0.0f, "default params carry a temperature");
    check(defaults.seed < 0, "default params leave the runtime's own seeding");

    // A path that does not exist must fail cleanly rather than crash, and must
    // leave a message behind. This is the call that proves we reached the
    // runtime: engine settings creation happens inside libCLiteRTLM.
    KoiLmEngine* missing = koilm_model_load("/nonexistent/model.litertlm", nullptr,
                                            KOILM_BACKEND_CPU, 0, 0);
    check(missing == nullptr, "loading a missing model returns null");
    check(koilm_last_error()[0] != '\0', "a failed load leaves an error message");
    std::printf("       reported: %s\n", koilm_last_error());

    const char* model_path = std::getenv("KOI_TEST_MODEL");
    if (model_path == nullptr) {
        std::printf("\nKOI_TEST_MODEL not set; skipping generation.\n");
        return failures == 0 ? 0 : 1;
    }

    std::printf("\nLoading %s\n", model_path);
    KoiLmEngine* engine =
        koilm_model_load(model_path, nullptr, KOILM_BACKEND_CPU, 0, 0);
    check(engine != nullptr, "the model loads");
    if (engine == nullptr) {
        std::printf("       reported: %s\n", koilm_last_error());
        return 1;
    }

    KoiLmSessionParams params = koilm_default_session_params();
    params.max_tokens = 32;
    KoiLmConversation* conversation =
        koilm_session_create(engine, params, "You are terse.");
    check(conversation != nullptr, "a conversation opens");

    if (conversation != nullptr) {
        char buffer[1 << 16];
        const int written =
            koilm_generate(conversation, "Say hello.", nullptr, buffer, sizeof(buffer));
        check(written > 0, "unconstrained generation returns text");
        if (written > 0) {
            std::printf("       reply: %s\n", buffer);

            // The overflow path, without generating again: a buffer too small to hold the
            // reply must report the size it needed and leave the reply collectable.
            char tiny[4];
            const int needed = koilm_last_response(tiny, sizeof(tiny));
            check(needed == written, "an undersized buffer reports the size needed");

            std::string collected(static_cast<size_t>(needed) + 1, '\0');
            const int again = koilm_last_response(&collected[0], static_cast<int>(collected.size()));
            check(again == written && std::strcmp(collected.c_str(), buffer) == 0,
                  "the reply survives a buffer that was too small");
        } else {
            std::printf("       reported: %s\n", koilm_last_error());
        }

        const char* schema =
            "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},"
            "\"required\":[\"city\"]}";
        const int constrained = koilm_generate(
            conversation, "Name a capital city.", schema, buffer, sizeof(buffer));
        check(constrained > 0, "schema-constrained generation returns text");
        if (constrained > 0) {
            std::printf("       reply: %s\n", buffer);
        } else {
            std::printf("       reported: %s\n", koilm_last_error());
        }

        koilm_session_free(conversation);
    }

    koilm_model_free(engine);
    std::printf("\n%s\n", failures == 0 ? "all checks passed" : "FAILURES");
    return failures == 0 ? 0 : 1;
}
