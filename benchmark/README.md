# On-device LLM benchmark harness

Measures llama.cpp and LiteRT-LM on real Android hardware through Firebase Test Lab, and emits
JSON that the analysis tool turns into statistics, CSV, Markdown and charts.

```
benchmark/
├── core/          the harness: engine adapters, protocol, schema, Android instrumentation
├── app/           the Android app: inference service + OpenAI server (its own build)
├── fixtures/      prompt corpus + the script that generates it
├── scripts/       Firebase Test Lab execution and device-matrix validation
└── analysis/      analyze_results.py
```

## What is actually being compared

"TFLite" and LiteRT-LM are not the same product. LiteRT core (TFLite's current name) is tensor
in, tensor out — no tokenizer, no sampler, no chat — and cannot generate text at all. The second
engine here is **LiteRT-LM**, Google's LLM layer, which is what `:backends:litertlm` binds.

## Metrics, and where each number comes from

**Every number is measured by the harness, above the engine, with one clock.** No backend
reports anything about itself. An adapter's only job is to turn a model path into a `Flow` of
chunks; `measureGeneration` timestamps the first one, counts the rest, and stops the clock.

That is the whole methodology. The alternative — each engine reporting its own timings — was
tried and thrown away: llama.cpp could stamp its decode loop, LiteRT-LM's Android SDK computes
its own figures, and its Apple binding computes none, which is three definitions of "time to
first token" in one results file and no way to compare any two of them. Measuring one layer
higher costs a little accuracy (the number includes the JNI or cinterop hop the chunk travelled
through) and buys the only thing that matters: the same number meaning the same thing
everywhere. It is also the number a caller experiences, since a token they have not received
has not arrived.

| metric | how |
|---|---|
| time to first chunk | harness clock, stamped when the first chunk reaches it |
| total latency | harness clock, ask to last chunk |
| chunks, chunks/sec | counted by the harness, from the first chunk |
| tokens, tokens/sec | harness counts the reply with the engine's own tokenizer |
| model load | harness clock around `initialize` |
| peak PSS, thermal, battery | Android platform APIs, sampled by the harness |

**Tokens are counted, not inferred.** Both backends expose their model's tokenizer, and the
harness calls it on the finished reply after the clock has stopped. So tokens/sec is comparable
across engines for the same reason the timings are: the harness does it, identically, rather than
each engine reporting its own number.

**Chunks are emissions, not tokens.** llama.cpp emits one token per chunk, because the facade's
pull loop returns one sampled token per call. LiteRT-LM emits whatever it emits — on the models
tested it is also one token per chunk, which is visible in the data rather than assumed, since
both engines report 32 chunks under a 32-token cap. The schema calls them chunks anyway, and
every throughput table prints the chunk count beside the rate, because the day an engine batches
its output is the day a "tokens/sec" column would quietly start comparing different things.

What no longer exists, deliberately: token counts from inside an engine, prefill/decode splits,
and `GenerationTelemetry` in `:core`. The library exposes streaming — useful to any caller
showing text as it arrives — and nothing benchmark-shaped at all.

Memory, thermal and battery come from Android APIs (`Debug.getMemoryInfo`, `PowerManager`'s
thermal status, `BatteryManager`, `/proc/self/status`, `cpufreq`) and are **null off Android** —
the host probe reports nothing rather than substituting laptop numbers. Battery percentage is
labelled coarse on purpose: FTL devices are mains powered and the level moves in whole percent,
so a short run reads zero regardless of what it drew. `BATTERY_PROPERTY_ENERGY_COUNTER` is read
where the device implements it, and rejected when it returns the usual `Long.MIN_VALUE`.

## Running it locally

The host target is macOS arm64 — the only place outside Android where **both** engines execute.
It verifies the harness, not the hardware: no PSS, no thermal, no battery.

```bash
# 1. Build the native pieces once.
cd backends/llamacpp/native
cmake --preset macosArm64 && cmake --build --preset macosArm64 -j$(sysctl -n hw.ncpu)
mkdir -p ../build/prebuilt/macos_arm64
find build/macosArm64 -name "*.a" | xargs libtool -static -o ../build/prebuilt/macos_arm64/libkoinference-facade.a
cd ../../..

# 2. Fetch the models — the same weights in both formats. See "Fairness" below.
curl -fsSLO https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF/resolve/main/LFM2.5-1.2B-Instruct-Q4_0.gguf
curl -fsSLO https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct/resolve/main/LFM2.5-1.2B-Instruct_int4.litertlm

# 3. Run the protocol against both engines and write JSON.
KOI_TEST_GGUF=$PWD/LFM2.5-1.2B-Instruct-Q4_0.gguf \
KOI_TEST_LITERTLM=$PWD/LFM2.5-1.2B-Instruct_int4.litertlm \
KOI_BENCH_FIXTURES=$PWD/benchmark/fixtures/prompts.json \
KOI_BENCH_OUT=$PWD/results/raw \
    ./gradlew :benchmark:core:macosArm64Test
```

## Running it on Firebase Test Lab

Nothing here hard-codes a project. Authenticate first:

```bash
gcloud auth login                       # or: export GOOGLE_APPLICATION_CREDENTIALS=key.json
export FIREBASE_PROJECT_ID=your-project
export FTL_RESULTS_BUCKET=gs://your-bucket   # optional, enables automatic artifact download
```

Check the matrix against the live catalogue — FTL model ids are Google codenames and they are
retired without notice:

```bash
./benchmark/scripts/validate-device-matrix.sh benchmark/scripts/devices.yaml
./benchmark/scripts/validate-device-matrix.sh --catalog    # what is offered right now
./benchmark/scripts/validate-device-matrix.sh --suggest    # generate a starting matrix
```

Then run. `--dry-run` prints the exact `gcloud` invocations without executing anything:

```bash
./benchmark/scripts/run-ftl-benchmark.sh \
    --matrix benchmark/scripts/devices.yaml \
    --engine all \
    --model gs://your-bucket/models/LFM2.5-1.2B-Instruct_int4.litertlm \
    --model-id LFM2.5-1.2B-Instruct \
    --quantization int4 \
    --model-sha256 "$(shasum -a 256 LFM2.5-1.2B-Instruct_int4.litertlm | cut -d' ' -f1)" \
    --iterations 5
```

One `gcloud` invocation per (device, engine), so each engine gets a process no other engine has
heated, and one failing shard costs only itself. Artifacts land in `results/raw/` and
`results/logs/`.

## Analysing

```bash
python3 benchmark/analysis/analyze_results.py results/
# results/csv/samples.csv     one row per measured iteration, nothing dropped
# results/csv/summary.csv     min/max/mean/median/p50/p90/p95/stddev per group
# results/markdown/summary.md tables per device, plus a comparability section
# results/charts/*.png        one per metric (needs matplotlib)
```

Statistics are computed here rather than on the device so a suspicious mean can always be traced
to the iterations behind it. Records that are not `SUCCESS` are counted and listed, never
averaged. A metric with no data produces no bar rather than a zero-height one.

## Reproducibility, per engine

Sampling defaults to temperature 0, which both backends treat as argmax — llama.cpp natively,
LiteRT-LM through top-k of 1, because its own sampler keeps sampling at temperature 0.

The two engines still differ in what repeats:

* **llama.cpp** repeats within a run: identical iterations produce identical text, which is
  visible in the sample rows.
* **LiteRT-LM** repeats across *engines*, not across iterations of one. Its sampler RNG is
  seeded when the engine is created and keeps advancing, and reopening a conversation does not
  rewind it. Its first generation after loading also differs from every later one — systematically,
  not as noise — while later ones agree with each other. Warmup iterations are therefore discarded
  for correctness on this backend, not only to skip a cold cache.

This affects how variance in the results should be read, not whether the comparison is fair:
both engines answer the same prompt with the same output budget, and every timing comes from
the same harness code either way.

## The protocol

Per (engine, workload): initialize → load model → *n* warmup iterations → *n* measured
iterations → optional sustained phase → teardown. Warmup samples are kept in their own field.
Sampling defaults to temperature 0 because llama.cpp's facade exposes no seed, so greedy
decoding is the only setting that makes both engines reproducible the same way; the seed is
recorded and applied to LiteRT-LM, which does have one.

Contamination is handled by process separation, not by hope. `-e engine all` in one process is
supported and the affected records carry a note saying the heap, page cache and SoC were not in
a cold state. The FTL script never does this.

## Fairness

The harness records enough to tell whether two results are comparable, and the analysis tool
says so out loud when they are not:

* model id, version and **SHA-256**, per record
* quantization label — passed in, never inferred from a file size
* prompt id and its checksum, so editing a prompt without changing its id is detectable
* the sampling actually applied, including `seedApplied=false` for llama.cpp
* the telemetry source

### The model

**LFM2.5-1.2B-Instruct is published in both formats**, which is what makes a like-for-like
comparison possible:

| engine | file | source |
|---|---|---|
| llama.cpp | `LFM2.5-1.2B-Instruct-Q4_0.gguf` (664 MB) | `LiquidAI/LFM2.5-1.2B-Instruct-GGUF` |
| LiteRT-LM | `LFM2.5-1.2B-Instruct_int4.litertlm` (702 MB) | `litert-community/LFM2.5-1.2B-Instruct` |

Same base weights, same 1.2B parameters, both 4-bit. **The quantization schemes are still not
identical** — GGUF `Q4_0` is blockwise 4-bit with per-block scales, LiteRT-LM's `int4` is its own
scheme with its own choice of which tensors stay at higher precision — so the report states the
difference rather than claiming equivalence. `Q4_K_M` is also published if you want the
higher-quality GGUF side of the comparison instead; record whichever you used.

Both engines are given the same output budget. This matters more than it sounds: LiteRT-LM caps
output per *conversation*, llama.cpp per session (`n_predict`), and until `maxOutputTokens` was
plumbed through the LiteRT-LM loader the two were being asked for different amounts of work —
llama.cpp stopped at 32 tokens while LiteRT-LM ran to its own stopping point and looked slower
for it.

## The Android app and its inference service

`benchmark/app` is a real app with two jobs: it hosts the inference service, and it is the APK
Firebase Test Lab installs beside the instrumentation test APK.

**Each engine runs in its own process** — `:llamacpp` and `:litertlm`, one manifest entry each —
and the app talks to them over AIDL. That is the whole reason they are services: a model's memory
is most of what anyone wants to measure, and in a shared process it arrives mixed with Compose, Ktor
and whatever else the app holds. Alone, `Debug.getMemoryInfo()` in that process is the model and its
engine, and `adb shell dumpsys meminfo` lists it separately. It also means a native crash — a CPU
without the instructions ggml was compiled for, say — takes down one engine rather than the app.

`android:process` is fixed per manifest entry, which is why there is a service class per backend
rather than one service told which engine to be.

**The benchmark runs inside the engine's process**, not the app's: `BenchmarkRunner` is constructed
there and only the finished results file crosses the boundary, as JSON. No reported timing includes
a binder round trip, and the memory readings describe the engine. The app is a UI and a results
table.

They are *foreground* services because Android kills background ones, and a sustained run that dies
at minute eleven of twenty has measured nothing.

### The app

Two screens.

**Benchmark** lists the engines this build ships, each with the models on the device it can read.
Tick the ones to run, press the button at the bottom, and the results arrive as a table — medians
per workload, with the note column carrying whatever the harness recorded. Engines run one after
another, never together: two decoding at once share an SoC and a thermal budget, and the numbers
would describe the contention.

An engine this device cannot run is shown disabled with the reason rather than hidden, because
"why is llama.cpp missing" is exactly the question hiding it would create.

**Serve** picks one engine to put behind the HTTP server, for the Python clients below.

### Driving it from a shell

A matrix is a script, not a screen. `BenchmarkService` runs the same code the UI does — same
orchestration, same suite, same one-engine-at-a-time — with no tapping:

```bash
adb shell am start-foreground-service \
    -n io.github.lemcoder.koinference.benchmark.app/.service.BenchmarkService \
    --es engines all \
    --es model /data/local/tmp/koinference/LFM2.5-1.2B-Instruct-Q4_0.gguf \
    --es promptSet short_generation_v1 \
    --ei iterations 3 --ei warmup 1 --ei maxNewTokens 32

adb logcat -s koinference-benchmark:I
```

```
skipped LiteRT-LM: cannot read LFM2.5-1.2B-Instruct-Q4_0.gguf
running [llama.cpp, Cera] with {promptSet=short_generation_v1, iterations=3, ...}
RESULT llama.cpp short_generation_v1 tok/s=40.6 ttft=342.9ms tokens=32 chunks=32 peakPss=2861.5MB afterLoad=1339.8MB afterRun=35.6MB
RESULT cera      short_generation_v1 tok/s=12.8 ttft=2042.3ms tokens=32 chunks=32 peakPss=718.1MB afterLoad=50.9MB afterRun=30.6MB
results written to /storage/emulated/0/Android/data/.../files/benchmark-results.json
```

| extra | |
|---|---|
| `--es engines` | ids or labels, comma separated, or `all` (the default) |
| `--es model` | optional; applies to every engine that can read that container. An engine that cannot is **skipped by name**, not silently — one GGUF runs on both GGUF engines and LiteRT-LM says why it sat out |
| `--es out` | where to write the merged results; defaults to the app's external files dir |
| everything else | handed to the harness untouched, so `promptSet`, `iterations`, `warmup`, `maxNewTokens`, `maxContextTokens`, `threads`, `seed` and the rest mean what they mean there |

With no `--es model`, each engine takes the first model it can read from the search paths, so
`--es engines litert-lm` alone is a complete run.

Every record is logged as one `RESULT` line, so a scripted sweep needs no file pulled to be read —
and the merged JSON is the harness's own schema, not a second one this app invented.

```bash
adb install -r benchmark/app/build/outputs/apk/benchmark/koinference-benchmark-app-benchmark.apk
adb push LFM2.5-1.2B-Instruct-Q4_0.gguf /data/local/tmp/koinference/
```

Models are looked for in `/sdcard/Download/koinference`, `/data/local/tmp/koinference` and the app's
own external files directory. The engine that reads a container is the backend's own answer —
`.gguf` to llama.cpp, `.litertlm`/`.task` to LiteRT-LM — so a model appears under whichever engine
can load it.

The server is also startable from a shell, for a scripted run that should not need anyone to tap
anything:

```bash
adb shell am start-foreground-service \
    -n io.github.lemcoder.koinference.benchmark.app/.net.WebServerService \
    --es backend llama.cpp \
    --es modelPath /data/local/tmp/koinference/LFM2.5-1.2B-Instruct-Q4_0.gguf \
    --ei maxNewTokens 256
```

### The OpenAI-compatible API

| endpoint | |
|---|---|
| `GET /v1/models` | the loaded model, with its engine and path |
| `POST /v1/chat/completions` | with `"stream": true` for SSE, without it for one JSON reply |
| `GET /healthz` | liveness |
| `GET /koinference/device` | the device as the harness's own probe reports it |
| `GET /koinference/memory` | PSS/native/Java heap **of the engine's process**, asked for over the binder |

Compatible so that clients people already have work unchanged:

```bash
curl http://<device-ip>:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"LFM2.5-1.2B-Instruct-Q4_0","messages":[{"role":"user","content":"Say hello."}],"stream":true}'
```

`usage` is deliberately absent from responses. Filling it in would mean counting SSE events and
calling them tokens, which is the one thing this project keeps refusing to do.

**The server binds `0.0.0.0` with no authentication.** Anyone who can reach the device can drive
the model and read its output. That is a deliberate choice for a benchmark device on a lab
network; pass `--es bind 127.0.0.1` and use `adb forward tcp:8080 tcp:8080` anywhere else.

The server itself runs in the *app* process while the model stays in the engine's, so serving a
model cannot contaminate the memory numbers it reports — `/koinference/memory` asks the engine
process for its own.

### Benchmarking it from Python

```bash
python3 benchmark/analysis/openai_bench.py http://<device-ip>:8080 \
    --prompt-id short_generation_v1 --iterations 5 --out results/raw
python3 benchmark/analysis/analyze_results.py results/
```

Standard library only. It measures time to first SSE chunk and streaming throughput from the
client, reads the inference process's memory from `/koinference/memory`, and writes the same
schema `:benchmark:core` writes, so both kinds of run land in one analysis.

Those numbers are **not** the on-device harness's numbers: they include HTTP, serialisation and
the network. Every record from this client says so in its notes, and its device label is
`http-client` so the two never merge into one row.

## Why the app is its own Gradle build

AGP 9 compiles Kotlin itself — `org.jetbrains.kotlin.android` is rejected as unnecessary — so an
application module with Kotlin sources is fine. What is not fine is applying
`com.android.application` in a build that has the Kotlin Multiplatform plugin on its classpath:
AGP tries to create a `KotlinAndroidTarget` and hits the variant API it removed
(`NoClassDefFoundError: com/android/build/gradle/api/BaseVariant`).

So `benchmark/app` is a separate build that includes the main one, rather than the other way
round, and depends on `:benchmark:core` through a composite substitution. Running two toolchains
in one daemon exhausts Metaspace, which shows up as unrelated task-creation failures, so that
build raises the limit in its own `gradle.properties`.

## Adding an engine

Implement `BenchmarkInferenceEngine` in `benchmark/core`, add it to `availableEngines()`, and add
its id to the script's `ENGINE_LIST`. The protocol, schema and analysis tool need no changes. If
the engine can report its own timings, map them to `GenerationTelemetry` with
`TelemetrySource.ENGINE`; if it can only be observed from outside, use `STREAM_FIRST_CHUNK` and
leave what you cannot measure null.
