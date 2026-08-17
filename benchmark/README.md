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

Time to first token is never inferred by dividing a total by a token count. Each engine reports
it from where it happens, and every sample records which:

| | llama.cpp | LiteRT-LM (Android) | LiteRT-LM (Apple/host) |
|---|---|---|---|
| source | `ENGINE` | `STREAM_FIRST_CHUNK` | none |
| TTFT | stamped in the decode loop | first streamed chunk timestamped | ✗ |
| prefill ms | ✓ | ✗ (not separable from the first chunk) | ✗ |
| prompt / generated tokens | ✓ | ✗ | ✗ |
| decode tokens/sec | ✓ | ✗ (no token count to divide by) | ✗ |

Two consequences worth stating plainly:

* **Decode tokens/sec is not available for LiteRT-LM on Android.** Its SDK computes exactly
  these metrics — `Conversation.getBenchmarkInfo()` is in the AAR — but the method is `internal`
  in the Kotlin metadata, so no consumer can call it, and the runtime ships with benchmarking
  off because nothing in the public `EngineConfig` turns it on. Counting streamed chunks or
  splitting the reply on whitespace would produce a token count the binding cannot actually
  produce, so the field is null and the record carries a note. Wall-clock latency and TTFT are
  comparable; throughput per token is not, until Google exposes the accessor.
* **`ENGINE` and `STREAM_FIRST_CHUNK` values are never averaged together.** The second includes
  the binding that delivered the chunk. The analysis tool reports the source in every table and
  flags a comparison that mixes them.

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

# 2. Fetch the models. Same base weights are not available in both formats at this size —
#    see "Fairness" below.
curl -fsSLO https://huggingface.co/ggml-org/models/resolve/main/tinyllamas/stories260K.gguf
curl -fsSLO https://huggingface.co/litert-community/SmolLM2-135M-Instruct/resolve/main/SmolLM2_135M_Instruct.litertlm

# 3. Run the protocol against both engines and write JSON.
KOI_TEST_GGUF=$PWD/stories260K.gguf \
KOI_TEST_LITERTLM=$PWD/SmolLM2_135M_Instruct.litertlm \
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
    --model gs://your-bucket/models/SmolLM2_135M_Instruct.litertlm \
    --quantization q8 \
    --model-sha256 "$(shasum -a 256 SmolLM2_135M_Instruct.litertlm | cut -d' ' -f1)" \
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

**The two engines cannot currently run the same weights.** llama.cpp needs GGUF, LiteRT-LM needs
`.litertlm`, and no published model exists in both formats at a size that fits CI. The local
verification therefore compares stories260K (f32 GGUF) against SmolLM2-135M (q8 `.litertlm`) —
different models *and* different quantization — and the generated Markdown says exactly that
under "Comparability". For a real comparison, convert one base model to both formats, record
both checksums, and pass `--quantization` honestly.

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
