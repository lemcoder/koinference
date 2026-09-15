# Device benchmark harness — plan

The goal is a repeatable, public record of **how fast on-device LLM inference actually is**, on
real phones, measured the same way every time, with a website anyone can open.

## Thesis

Speed and memory are hard numbers; accuracy is not. There are dozens of accuracy leaderboards and
they are all "trust me bro" — the number depends on the harness, the prompt formatting, the judge.
Inference speed on a named phone is a fact: tokens per second, time to first token, peak PSS,
battery temperature. **Speed is the product; accuracy is a secondary column** we report because we
can, not because it is the point.

Two KPIs are load-bearing:

1. **Inference speed** — decode `tok/s`, time to first token (TTFT), sustained throughput.
2. **Memory** — peak PSS, after-load, after-run.

Everything else on a record (device model, SoC, RAM, thermal, battery) is context that makes those
two numbers *mean* something.

## Decisions (locked)

| Question | Answer | Why |
|---|---|---|
| Data source of truth | **Static NDJSON in the repo** | GitHub Pages is static; a versioned, diffable file needs no backend and is reproducible offline. |
| Reference model | **Gemma 3 1B, Q4_0** | Official GGUF *and* LiteRT-LM `.task` exist, so the same model runs on both engines later; ~0.7 GB fits every DUT under the load guard. |
| v1 backend scope | **llama.cpp only** | Get clean cross-device numbers with one engine and one quant first; prove the FTL→data→site pipeline before multiplying the matrix. |
| Cross-device speed anchor | **One reference GGUF on every device** | Same model + quant + engine is the only honest cross-device comparison. Cross-*engine* is a separate axis, added in v2. |

A Google Sheet is **not** the backbone (it is not versioned and forces the static site to pull over
CORS). It stays an optional mirror in v2 if spreadsheet views are wanted.

## What already exists — do not rebuild it

A surprising amount of this is done, and the plan is mostly *completion and two new legs*, not
greenfield.

- **FTL orchestration** — `benchmark/scripts/run-ftl-benchmark.sh` builds both APKs, reads the
  device matrix, shards per engine, pushes the model and prompts with `--other-files`, pulls
  `/sdcard/Download/koinference` back with `--directories-to-pull`, and passes `ftlModelId` /
  `ftlVersion` / `runId` as instrumentation args (nothing on the device knows which matrix entry it
  is). Model arrives as a `gs://` URI; results land in an FTL results bucket.
- **Device matrix** — `benchmark/scripts/devices.yaml` (Pixel 8 / Tensor G3, Pixel 6a, Galaxy S22
  Ultra, Pixel 5). `validate-device-matrix.sh` checks ids against the live catalogue.
- **The instrumented entry FTL runs** — `BenchmarkInstrumentation.runBenchmark`, one engine per
  invocation, writes `benchmark-results.json` to the pulled directory. `-e engine all` exists but is
  marked contaminated (second engine on a heap/cache/SoC the first warmed).
- **The result schema** — `benchmark/core/.../result/`: `DeviceInfo` (manufacturer, model, SoC
  manufacturer+model, ABI, core count, per-core max freq, RAM MB, **`ftlModelId` / `ftlVersion` /
  `isEmulator`** already present), `EngineInfo`, `WorkloadInfo`, `MemoryMetrics`, `ThermalMetrics`,
  `BatteryMetrics`, `SustainedMetrics`, `GenerationSample`, `BenchmarkRecord`, `BenchmarkFile`.
  `explicitNulls = true` on purpose — an absent metric is data, not noise.
- **Device metadata capture** — `AndroidProbe` reads `Build.SOC_MODEL`, cpufreq, RAM, emulator
  detection. **This is the "gather whatever we can from the system" ask, and it is already built.**
- **Analysis** — `benchmark/analysis/analyze_results.py` validates every JSON against the schema and
  emits `csv/` (samples, summary), `markdown/summary.md`, fairness notes, and some charts. Drops any
  non-`SUCCESS` record before computing a statistic.

So the harness measures the right things and FTL can already drive it. The gaps are the pipeline's
two ends — a **reference model**, a **website**, an **accumulating dataset**, **CI automation** — and
one new measurement axis, **RAG on/off**.

## Gap analysis → what this plan builds

| Piece | State | Work |
|---|---|---|
| Reference model (Gemma 3 1B Q4_0) | runs LFM2.5 today | host GGUF on GCS, pin sha256, make it the matrix default |
| RAG on/off axis | none | `WorkloadInfo.ragMode`, on-device retrieval, corpus fixture |
| Accumulating dataset | per-run csv/md only | flatten raw JSON → append `results/site/data.ndjson` |
| Website (GitHub Pages) | none | static site reading the NDJSON |
| CI automation | manual script | `device-benchmark.yml` → FTL → merge → deploy Pages |
| More engines / Sheet mirror | v2 | after the pipeline is proven |

## Phases

### Phase 1 — Reference model + a live FTL run (llama.cpp, Gemma 3 1B)
Prove the existing script end-to-end against the model we will standardise on.

- Fetch **Gemma 3 1B Q4_0 GGUF**, upload to a GCS bucket, record its sha256.
- Run `run-ftl-benchmark.sh --engine llama.cpp --model gs://…/gemma-3-1b-it-Q4_0.gguf
  --model-id gemma-3-1b-it --quantization Q4_0 --model-sha256 <sha>` against `devices.yaml`.
- Confirm: results pull back per shard, schema validates, `analyze_results.py` produces a summary.
- Deliverable: one real multi-device run of the reference model, committed under `results/`.
- Prereqs (user's to provision): a **GCP project on Blaze billing** (FTL physical devices are
  billed; the free tier has no physical-device minutes), a **service account** with
  Test Lab + Storage access, and the buckets. These become CI secrets in Phase 5.

### Phase 2 — Accumulating dataset for the site
`analyze_results.py` summarises *one* run; the site needs *every* run over time in one flat file.

- Add a `--emit-ndjson <path>` mode (or a small `to_site.py`) that flattens each raw
  `BenchmarkRecord` into one denormalised row and **appends** to `results/site/data.ndjson`:

  ```json
  {"runId":"20260915T…","ts":"2026-09-15T…","device":{"model":"shiba","label":"Pixel 8",
   "soc":"Tensor G3","ramMb":8192,"cores":9,"abi":"arm64-v8a","ftlModelId":"shiba","ftlVersion":34},
   "engine":{"id":"llama.cpp","version":"b10516","modelId":"gemma-3-1b-it","quantization":"Q4_0"},
   "workload":{"promptId":"short_generation_v1","ragMode":"OFF","maxNewTokens":128},
   "kpi":{"decodeTokPerSecP50":22.4,"ttftMsP50":140.6,"peakPssMb":1180,
   "batteryTempPeakC":34.1,"status":"SUCCESS"}}
  ```

- One line per (run, device, engine, workload). Medians/percentiles are precomputed for the site;
  the full raw JSON stays in GCS (and optionally `results/raw/`) so nothing is lost.
- **Bloat control**: `data.ndjson` is a rollup, not the raw samples. If it grows large, prune by
  run age and keep the raw in GCS; the file is meant to be a few thousand lines, not a database.

### Phase 3 — The website (GitHub Pages)
Static, no server. `fetch()` the NDJSON, render.

- Location `site/` (source) → built/copied to Pages. Stack: plain HTML + a pinned charting lib
  (Chart.js), no framework needed; a light Vite build only if it earns its keep.
- Views:
  - **Speed leaderboard** — decode `tok/s` by device for the reference model (bar, sorted).
  - **TTFT** and **peak PSS** by device.
  - **RAG on vs off** — TTFT/PSS delta per device (the Phase 4 payoff).
  - **Device cards** — model, SoC, RAM, peak battery temp, core layout.
  - Filters: device / SoC / model / engine / RAG mode.
- Honesty carried from the schema: a `null` KPI renders as "not measured", never as zero; failed
  records are excluded from statistics but shown as failures.

### Phase 4 — RAG on/off axis
The one genuinely new *measurement*. RAG's speed cost is almost entirely **prefill/TTFT**: retrieved
context lengthens the prompt, so TTFT and KV-cache PSS rise while decode `tok/s` (bandwidth-bound on
the model) barely moves. That TTFT delta is the hard number worth publishing.

- Schema: add `WorkloadInfo.ragMode: OFF | ON`, `ragK`, `ragContextChars` (all defaulted, so old
  files still parse).
- On device, RAG-on path: embed the query with the **ONNX embedding backend** (already in the repo),
  cosine-rank a small fixed **corpus fixture** pushed alongside `prompts.json`, prepend the top-`k`
  chunks to the prompt, then generate. Retrieval is deterministic (fixed corpus, fixed query) so the
  measurement is reproducible.
- Report both modes per device; the site shows the delta. Keep the corpus and `k` small and fixed —
  this measures the *cost of RAG on decode latency*, not retrieval quality.
- Accuracy note: any RAG-*quality* number (recall@k / nDCG) is the secondary, client-side eval that
  already lives in `benchmark/analysis/rag_eval.py`. It is not on the device speed path and is not a
  gate here.

### Phase 5 — CI automation + Pages deploy
Turn the manual script into a workflow.

- `.github/workflows/device-benchmark.yml`, `workflow_dispatch` + a nightly `cron`:
  1. Authenticate to GCP (service-account secret).
  2. Build the app + androidTest APKs.
  3. `run-ftl-benchmark.sh --engine llama.cpp --model gs://…/gemma-3-1b-it-Q4_0.gguf …`.
  4. Pull raw JSON from the FTL results bucket; flatten → append `results/site/data.ndjson`;
     commit (a bot commit to a `results` branch, to keep churn off `main`).
  5. Build `site/` → `actions/deploy-pages`.
- Secrets: `FIREBASE_PROJECT_ID`, `GCP_SA_KEY`, `FTL_RESULTS_BUCKET`, model bucket. None hard-coded —
  the script already refuses to run without `FIREBASE_PROJECT_ID`.
- **Not** per-PR — FTL device-minutes cost money and take real wall-clock. Nightly + manual only.

## v2 and beyond (out of scope for this plan, listed so it is not forgotten)
- **More engines** — LiteRT-LM (Gemma 3 1B `.task`), then Cera, ExecuTorch. Cross-engine on the same
  device becomes a second axis on the site. `run-ftl-benchmark.sh` already defaults to
  `llama.cpp,litert-lm`; v1 just passes `--engine llama.cpp`.
- **Google Sheet mirror** — a CI step that appends the same rows via a service account, for people
  who want pivot tables. The site keeps reading the NDJSON.
- **More devices** — the matrix is one file; widen once cost is understood.

## Risks / things that will bite
- **FTL cost and quota.** Physical-device minutes are billed (Blaze). Matrix × engines × workloads ×
  iterations is real money and ~tens of minutes per shard. Curate the matrix; keep iteration counts
  honest but not extravagant.
- **Model delivery size.** ~0.7 GB pushed to each device via `--other-files` from GCS. Watch the FTL
  device storage limit and per-run upload; the load guard we just merged refuses a device that
  cannot hold it, which is the right failure rather than a mid-run OOM.
- **Single-run noise.** Already a known lesson (docs/performance.md): a device swings 2× on run
  order. Keep `iterations`/`warmup` and interleave; the schema keeps every sample so noise is
  visible, not averaged away.
- **Repo bloat from committed results.** Mitigated by NDJSON-as-rollup + raw in GCS + a results
  branch.
- **`.task` for LiteRT-LM in v2.** Gemma's official `.task` must match the GGUF closely enough that a
  cross-engine number is fair; note the quant will differ (int4 vs Q4_0) and label it.

## Open questions for you
1. **GCP project + billing** — is there a project on Blaze we use, or should provisioning it be the
   first Phase-1 task?
2. **Results location** — a dedicated `results` branch (keeps `main` clean) vs a `results/` dir on
   `main`. Recommendation: `results` branch.
3. **Nightly cadence** — every night, or manual-dispatch only until the numbers stabilise?
4. **Site host path** — project Pages at `/<repo>/` is fine, or a custom domain later?
