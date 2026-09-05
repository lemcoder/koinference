#include "koinference_whisper.h"

#include "whisper.h"

#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <memory>

namespace {

// Thread-local, so a failure on one thread cannot be read as another's.
thread_local std::string g_last_error;

void set_error(const std::string& message) { g_last_error = message; }

// snprintf's contract: report what the text needs, write only if it fits.
int copy_out(const std::string& text, char* out, int out_size) {
    const int needed = static_cast<int>(text.size());
    if (out != nullptr && out_size > needed) {
        std::memcpy(out, text.c_str(), needed + 1);
    }
    return needed;
}

whisper_full_params default_params(const char* language, int threads) {
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    // Segments as they are produced rather than at the end; the streaming path needs them early
    // and the blocking path drains the same loop.
    params.single_segment = false;
    if (language != nullptr && language[0] != '\0') {
        params.language = language;
    }
    if (threads > 0) {
        params.n_threads = threads;
    }
    return params;
}

} // namespace

struct KoiwModel {
    whisper_context* ctx = nullptr;
};

// A transcription in flight: whisper on its own thread, segments in a queue, Kotlin pulling.
struct KoiwStream {
    std::thread worker;
    std::mutex mutex;
    std::condition_variable ready;
    std::deque<std::string> segments;
    bool finished = false;
    std::string failure;

    void push(std::string segment) {
        {
            std::lock_guard<std::mutex> lock(mutex);
            segments.push_back(std::move(segment));
        }
        ready.notify_one();
    }

    void finish(std::string error) {
        {
            std::lock_guard<std::mutex> lock(mutex);
            failure = std::move(error);
            finished = true;
        }
        ready.notify_all();
    }
};

int koiw_system_info(char* out, int out_size) {
    return copy_out(whisper_print_system_info(), out, out_size);
}

KoiwModel* koiw_model_load(const char* model_path, int use_gpu) {
    if (model_path == nullptr) {
        set_error("no model path");
        return nullptr;
    }

    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = use_gpu != 0;

    whisper_context* ctx = whisper_init_from_file_with_params(model_path, params);
    if (ctx == nullptr) {
        set_error(std::string("whisper could not load ") + model_path);
        return nullptr;
    }

    KoiwModel* model = new KoiwModel();
    model->ctx = ctx;
    return model;
}

void koiw_model_free(KoiwModel* model) {
    if (model == nullptr) return;
    if (model->ctx != nullptr) whisper_free(model->ctx);
    delete model;
}

int koiw_transcribe(
    KoiwModel* model,
    const float* samples,
    int n_samples,
    const char* language,
    int threads,
    char* out,
    int out_size) {
    if (model == nullptr || model->ctx == nullptr || samples == nullptr) {
        set_error("no model or no samples");
        return -1;
    }

    whisper_full_params params = default_params(language, threads);
    if (whisper_full(model->ctx, params, samples, n_samples) != 0) {
        set_error("whisper_full failed");
        return -1;
    }

    std::string text;
    const int segments = whisper_full_n_segments(model->ctx);
    for (int i = 0; i < segments; ++i) {
        text += whisper_full_get_segment_text(model->ctx, i);
    }

    return copy_out(text, out, out_size);
}

KoiwStream* koiw_transcribe_begin(
    KoiwModel* model,
    const float* samples,
    int n_samples,
    const char* language,
    int threads) {
    if (model == nullptr || model->ctx == nullptr || samples == nullptr) {
        set_error("no model or no samples");
        return nullptr;
    }

    KoiwStream* stream = new KoiwStream();

    // The samples are copied because the caller's array is Kotlin-owned and the worker outlives
    // this call. A few seconds of 16 kHz audio is a few hundred kilobytes; a use-after-free is
    // worse.
    auto owned = std::make_shared<std::vector<float>>(samples, samples + n_samples);

    whisper_full_params params = default_params(language, threads);
    params.new_segment_callback_user_data = stream;
    params.new_segment_callback = [](whisper_context* ctx, whisper_state*, int n_new, void* user_data) {
        auto* target = static_cast<KoiwStream*>(user_data);
        const int total = whisper_full_n_segments(ctx);
        for (int i = total - n_new; i < total; ++i) {
            target->push(whisper_full_get_segment_text(ctx, i));
        }
    };

    whisper_context* ctx = model->ctx;
    stream->worker = std::thread([stream, ctx, params, owned]() {
        // An exception crossing extern "C" is undefined behaviour, so the worker keeps its own.
        try {
            const int status = whisper_full(ctx, params, owned->data(), static_cast<int>(owned->size()));
            stream->finish(status == 0 ? std::string() : std::string("whisper_full failed"));
        } catch (const std::exception& failure) {
            stream->finish(failure.what());
        } catch (...) {
            stream->finish("unknown failure in whisper_full");
        }
    });

    return stream;
}

int koiw_transcribe_next(KoiwStream* stream, char* out, int out_size) {
    if (stream == nullptr) {
        set_error("no stream");
        return -1;
    }

    std::unique_lock<std::mutex> lock(stream->mutex);
    stream->ready.wait(lock, [stream]() { return !stream->segments.empty() || stream->finished; });

    if (!stream->segments.empty()) {
        const std::string segment = stream->segments.front();
        stream->segments.pop_front();
        lock.unlock();
        return copy_out(segment, out, out_size);
    }

    if (!stream->failure.empty()) {
        const std::string failure = stream->failure;
        lock.unlock();
        set_error(failure);
        return -1;
    }

    return 0;
}

void koiw_transcribe_end(KoiwStream* stream) {
    if (stream == nullptr) return;
    if (stream->worker.joinable()) stream->worker.join();
    delete stream;
}

int koiw_last_error(char* out, int out_size) { return copy_out(g_last_error, out, out_size); }
