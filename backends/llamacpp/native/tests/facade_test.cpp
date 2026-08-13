#include <gtest/gtest.h>
#include "koinference_facade.h"

#include <cstring>
#include <string>

// These tests exercise the facade API without a real model file.
// They verify: error handling, null-safety, and default parameters.
// Integration tests (requiring an actual GGUF file) live under tests/integration/.

class FacadeTest : public ::testing::Test {
protected:
    void SetUp() override    { koi_backend_init(); }
    void TearDown() override { koi_backend_free(); }
};

// ── backend ────────────────────────────────────────────────────────────────

TEST_F(FacadeTest, BackendInitFreeDoesNotCrash) {
    // SetUp/TearDown already exercises init + free once; do it again to ensure
    // repeated calls don't crash (llama.cpp allows multiple init/free cycles
    // as long as they are balanced).
    koi_backend_init();
    koi_backend_free();
}

TEST_F(FacadeTest, SystemInfoReturnsNonNullString) {
    const char* info = koi_system_info();
    ASSERT_NE(info, nullptr);
    EXPECT_GT(strlen(info), 0u);
}

// ── model ──────────────────────────────────────────────────────────────────

TEST_F(FacadeTest, ModelLoadReturnsNullForMissingFile) {
    KoiModel* model = koi_model_load("/nonexistent/path/model.gguf", 0);
    EXPECT_EQ(model, nullptr);
}

TEST_F(FacadeTest, ModelLoadReturnsNullForNullPath) {
    KoiModel* model = koi_model_load(nullptr, 0);
    EXPECT_EQ(model, nullptr);
}

TEST_F(FacadeTest, ModelFreeNullIsSafe) {
    koi_model_free(nullptr);  // must not crash
}

// ── session params ─────────────────────────────────────────────────────────

TEST_F(FacadeTest, DefaultSessionParamsAreReasonable) {
    KoiSessionParams p = koi_default_session_params();
    EXPECT_GT(p.n_ctx,      0);
    EXPECT_GT(p.n_threads,  0);
    EXPECT_NE(p.n_predict,  0);
    EXPECT_GT(p.temp,       0.0f);
    EXPECT_GE(p.top_k,      0);
    EXPECT_GE(p.min_p,      0.0f);
}

// ── session lifecycle ──────────────────────────────────────────────────────

TEST_F(FacadeTest, SessionCreateReturnsNullForNullModel) {
    KoiSession* session = koi_session_create(nullptr, koi_default_session_params());
    EXPECT_EQ(session, nullptr);
}

TEST_F(FacadeTest, SessionFreeNullIsSafe) {
    koi_session_free(nullptr);  // must not crash
}

// ── generate ───────────────────────────────────────────────────────────────

TEST_F(FacadeTest, GenerateReturnsErrorForNullSession) {
    char buf[256] = {};
    EXPECT_EQ(koi_generate(nullptr, nullptr, "hello", nullptr, buf, sizeof(buf)), -1);
}

TEST_F(FacadeTest, GenerateReturnsErrorForNullUserPrompt) {
    // Use a fabricated non-null session pointer; the NULL user_prompt check
    // must fire before the pointer is dereferenced.
    char buf[256] = {};
    EXPECT_EQ(koi_generate(reinterpret_cast<KoiSession*>(1), nullptr, nullptr, nullptr, buf, sizeof(buf)), -1);
}

TEST_F(FacadeTest, GenerateReturnsErrorForNullBuffer) {
    EXPECT_EQ(koi_generate(reinterpret_cast<KoiSession*>(1), nullptr, "hi", nullptr, nullptr, 256), -1);
}

TEST_F(FacadeTest, GenerateReturnsErrorForZeroBufferSize) {
    char buf[1];
    EXPECT_EQ(koi_generate(reinterpret_cast<KoiSession*>(1), nullptr, "hi", nullptr, buf, 0), -1);
}

// ── embed ──────────────────────────────────────────────────────────────────

TEST_F(FacadeTest, EmbedReturnsErrorForNullSession) {
    float buf[4];
    EXPECT_EQ(koi_embed(nullptr, "hello", buf, 4), -1);
}

TEST_F(FacadeTest, EmbedReturnsErrorForNullText) {
    float buf[4];
    EXPECT_EQ(koi_embed(reinterpret_cast<KoiSession*>(1), nullptr, buf, 4), -1);
}

TEST_F(FacadeTest, EmbedReturnsErrorForNullBuffer) {
    EXPECT_EQ(koi_embed(reinterpret_cast<KoiSession*>(1), "hello", nullptr, 4), -1);
}

// ── json schema → grammar ──────────────────────────────────────────────────

TEST_F(FacadeTest, SchemaToGrammarProducesARootRule) {
    char buf[4096] = {};
    const int len = koi_json_schema_to_grammar(
        R"({"type":"object","properties":{"city":{"type":"string"}},"required":["city"]})",
        buf, sizeof(buf));

    ASSERT_GT(len, 0);
    EXPECT_EQ(len, static_cast<int>(std::strlen(buf)));
    // common_sampler_init looks up "root"; a grammar without it is rejected at sampler build.
    EXPECT_NE(std::string(buf).find("root"), std::string::npos);
    EXPECT_NE(std::string(buf).find("city"), std::string::npos);
}

TEST_F(FacadeTest, SchemaToGrammarReturnsErrorForMalformedJson) {
    // The converter throws on this; the -1 proves the exception does not escape extern "C".
    char buf[256] = {};
    EXPECT_EQ(koi_json_schema_to_grammar("{not json", buf, sizeof(buf)), -1);
}

TEST_F(FacadeTest, SchemaToGrammarReturnsErrorWhenBufferIsTooSmall) {
    // Truncation would yield a grammar that parses but constrains something else.
    char buf[8] = {};
    EXPECT_EQ(koi_json_schema_to_grammar(R"({"type":"object"})", buf, sizeof(buf)), -1);
}

TEST_F(FacadeTest, SchemaToGrammarReturnsErrorForNullArguments) {
    char buf[64] = {};
    EXPECT_EQ(koi_json_schema_to_grammar(nullptr, buf, sizeof(buf)), -1);
    EXPECT_EQ(koi_json_schema_to_grammar(R"({"type":"object"})", nullptr, 64), -1);
    EXPECT_EQ(koi_json_schema_to_grammar(R"({"type":"object"})", buf, 0), -1);
}
