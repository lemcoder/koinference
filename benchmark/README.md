# On-device LLM benchmark harness

Measures llama.cpp and LiteRT-LM on real Android hardware through Firebase Test Lab, and emits
JSON that the analysis tool turns into statistics, CSV, Markdown and charts.

```
benchmark/
├── core/          the harness: engine adapters, protocol, schema, Android instrumentation
├── stub-app/      an empty APK, separate build (see "Why a stub app")
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
| model load | harness clock around `initialize` |
| peak PSS, thermal, battery | Android platform APIs, sampled by the harness |

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
  rewind it. Under argmax the text is stable in practice, but nothing guarantees iteration *n*
  matches iteration *n+1*.

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

## Why a stub app

AGP 9 cannot apply `com.android.application` anywhere in this build: it sees the Kotlin plugin on
the build classpath, tries to create a `KotlinAndroidTarget`, and that reaches for the variant
API AGP 9 removed (`NoClassDefFoundError: com/android/build/gradle/api/BaseVariant`). The only
Kotlin-capable Android plugin in AGP 9 is the multiplatform **library** one.

So the benchmark lives in `:benchmark:core`'s device test, which AGP builds as a
self-instrumenting test APK, and `benchmark/stub-app` is a separate included build — its own
plugin classpath, no Kotlin on it — producing the empty APK that FTL requires as the app under
test. Nothing in it ever runs.

## Adding an engine

Implement `BenchmarkInferenceEngine` in `benchmark/core`, add it to `availableEngines()`, and add
its id to the script's `ENGINE_LIST`. The protocol, schema and analysis tool need no changes. If
the engine can report its own timings, map them to `GenerationTelemetry` with
`TelemetrySource.ENGINE`; if it can only be observed from outside, use `STREAM_FIRST_CHUNK` and
leave what you cannot measure null.
