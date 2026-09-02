// C facade over whisper.cpp.
//
// Same shape and the same reasons as koinference_facade.h: one extern "C" surface that both legs
// bind — cinterop on Apple and Linux, generated JNI bridges on Android — so no logic lives twice.
//
// **Declaration order is an ABI.** The generated JNI bridges are numbered by position, so append
// new functions at the end and never delete one from the middle. See docs/backends.md.
#ifndef KOINFERENCE_WHISPER_H
#define KOINFERENCE_WHISPER_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct KoiwModel KoiwModel;
typedef struct KoiwStream KoiwStream;

// What whisper.cpp was compiled with, for a results file to record.
//
// Writes into a caller's buffer rather than returning a pointer, like every other string here: a
// `const char*` arrives on the JNI leg as an opaque long, with no way to read it back.
int koiw_system_info(char* out, int out_size);

// Loads a ggml whisper model. Returns NULL on failure; koiw_last_error says why.
KoiwModel* koiw_model_load(const char* model_path, int use_gpu);

void koiw_model_free(KoiwModel* model);

// Transcribes 16 kHz mono float samples in one call.
//
// Follows snprintf: returns the number of bytes the transcript needs, writing only if it fits, so
// a caller that guessed too small learns the size instead of losing the text. Negative on failure.
int koiw_transcribe(
    KoiwModel* model,
    const float* samples,
    int n_samples,
    const char* language,
    int threads,
    char* out,
    int out_size);

// Begins a transcription that reports segments as whisper produces them.
//
// whisper_full is synchronous and calls back mid-run, so the work goes on its own thread here and
// the segments land in a queue. Kotlin pulls; nothing of ours runs on a thread it does not own —
// the same arrangement the LiteRT-LM facade uses, for the same reason.
KoiwStream* koiw_transcribe_begin(
    KoiwModel* model,
    const float* samples,
    int n_samples,
    const char* language,
    int threads);

// Waits for the next segment. Returns its length in bytes, 0 when the transcription is finished,
// or negative on failure. Follows snprintf like koiw_transcribe.
int koiw_transcribe_next(KoiwStream* stream, char* out, int out_size);

// Ends the transcription and joins its thread. Safe before the stream is drained.
void koiw_transcribe_end(KoiwStream* stream);

// The last failure on this thread, or an empty string. Out-buffer for the same reason as above.
int koiw_last_error(char* out, int out_size);

#ifdef __cplusplus
}
#endif

#endif // KOINFERENCE_WHISPER_H
