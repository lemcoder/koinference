# Embedding evaluation

Two stdlib-only harnesses that score an OpenAI-compatible `/v1/embeddings` endpoint the same way
whether it is the phone or a frontier API — swap the model by URL. They answer two different
questions:

- **`embed_eval.py`** — *retrieval quality.* Given a query, does the embedding rank the right
  document first? Reported as nDCG@10 / Recall@10 / Recall@100 / MRR@10 on BEIR datasets, the same
  axis as the BEIR leaderboard, so a phone's `bge-small` sits next to `text-embedding-3`.
- **`rag_eval.py`** — *does retrieval help answer.* SQuAD v2 as an open-book task: a chat model
  answers each question twice — closed-book, and with the top-k passages an embedding model
  retrieved — scored by exact-match / token-F1. Sweeping embedding models shows how retrieval
  quality moves the RAG score for one fixed chat model.

Both take endpoints as `label|base_url|model|api_key_env` (the api-key part names an environment
variable; leave it empty for the phone, which has no auth). Stdlib only — no `numpy`, `openai`,
`datasets` or `beir`; datasets download as plain files and vector maths is pure Python, which is
fine at BEIR-subset sizes because the run is dominated by embedding over HTTP.

## Two ways to serve the "local" side

The harnesses only speak the OpenAI-compatible HTTP API — they never drive the Android app. So the
local model is whatever OpenAI-compatible server you point them at:

- **`llama-server` on the desktop (recommended for quality).** RAG accuracy depends only on the
  model + quantisation, not the hardware, so the same GGUF behind `llama-server` gives the same
  answers as the phone — with clean endpoints, no app-service juggling, and no one-model limit.
  Use this to compare a local model against a frontier one.
- **The phone (for on-device latency).** Only needed when the *hardware* is the thing under test;
  for answer accuracy it adds nothing over `llama-server` and costs the model-swap dance below.

### llama-server (no app, no device, no key for the local half)

```bash
# chat model on 8081, embedding model on 8082 -- two OpenAI-compatible servers
llama-server -m LFM2.5-1.2B-Instruct-Q4_0.gguf   --host 127.0.0.1 --port 8081 -c 4096 --no-webui &
llama-server -m bge-small-en-v1.5-q8_0.gguf --embedding --pooling cls \
             --host 127.0.0.1 --port 8082 -b 8192 -ub 8192 --no-webui &

python3 benchmark/analysis/rag_eval.py \
  --embed-endpoint "local-bge|http://127.0.0.1:8082/v1|bge-small|" \
  --chat-endpoint  "local-lfm2|http://127.0.0.1:8081/v1|lfm2|" \
  --limit 300 --top-k 5 --max-input-chars 2000
```

`-b 8192 -ub 8192` on the embedding server: `llama-server` embeds a whole request in one forward
pass bounded by `n_batch` (default 2048) and 500s when a batch of passages exceeds it.
`--max-input-chars 2000` caps each input to bge's 512-token window, which `llama-server` does not
truncate on its own (a longer sequence than its context is a 500). Neither flag is needed for
text-embedding-3, which batches server-side and takes 8k tokens.

**Local model + RAG vs frontier**, one fixed embedder so only the chat model varies:

```bash
export OPENAI_API_KEY=sk-...
# frontier chat, local model chat -- run twice, same --embed-endpoint, compare the tables
for CHAT in "local-lfm2|http://127.0.0.1:8081/v1|lfm2|" \
            "gpt|https://api.openai.com/v1|gpt-4o-mini|OPENAI_API_KEY"; do
  python3 benchmark/analysis/rag_eval.py \
    --embed-endpoint "oai-small|https://api.openai.com/v1|text-embedding-3-small|OPENAI_API_KEY" \
    --chat-endpoint "$CHAT" --limit 300 --top-k 5
done
```

The local model's closed-book row is the "small model alone" baseline; its rag row against the
frontier model's closed-book row is the headline — does on-device RAG match a frontier model's cold
knowledge.

## Serving the phone (on-device latency)

The phone's Ktor server (`benchmark:app`, `WebServerService`) serves `/v1/embeddings` **and**
`/v1/chat/completions`, but it holds **one** model at a time. So:

```bash
# embeddings (bge-small)
adb shell am start-foreground-service \
  -n io.github.lemcoder.koinference.benchmark.app/.net.WebServerService \
  --es backend ONNX --es modelPath /data/local/tmp/koinference/bge-small.onnx --es bind 127.0.0.1
adb forward tcp:8080 tcp:8080
curl -s localhost:8080/v1/models      # confirm bge-small is loaded
```

For `rag_eval.py` the chat model is a **separate** endpoint — it cannot be the same phone server
instance while that instance is serving embeddings. Use a frontier chat model, or serve the phone's
`llama.cpp` on 8080 *after* the embeddings are cached (see below), swapping `--es backend LLAMA_CPP
--es modelPath …LFM2….gguf`.

## Retrieval — `embed_eval.py`

```bash
# phone only, the default small datasets
python3 benchmark/analysis/embed_eval.py \
  --dataset scifact,nfcorpus \
  --endpoint "phone-bge|http://localhost:8080/v1|bge-small|" \
  --out benchmark/analysis/results/embeddings_phone.json

# phone vs frontier, side by side in one table (needs OPENAI_API_KEY exported)
python3 benchmark/analysis/embed_eval.py \
  --dataset scifact \
  --endpoint "phone-bge|http://localhost:8080/v1|bge-small|" \
  --endpoint "oai-small|https://api.openai.com/v1|text-embedding-3-small|OPENAI_API_KEY" \
  --endpoint "oai-large|https://api.openai.com/v1|text-embedding-3-large|OPENAI_API_KEY"
```

- `--dataset` — comma-separated BEIR names. Small enough for a phone: `scifact` (5.2k docs),
  `nfcorpus` (3.6k). Larger: `fiqa` (57k), `trec-covid` (171k) — use `--max-corpus N` to cap them
  (every judged document is always kept; only distractors are dropped).
- **The corpus size must be full for the numbers to match published ones.** A capped corpus has
  fewer distractors and inflates every metric — good for a quick smoke test, wrong for comparison.
- Corpus and query embeddings are cached under `--cache-dir` per `(dataset, model)`, keyed on the
  exact id set, so a rerun re-ranks in seconds and never re-embeds. `--no-cache` disables it.
- Progress is checkpointed per batch, so a phone kill or a dropped USB link mid-run loses at most
  one batch — rerun the same command and it resumes from where it stopped. (Both failure modes
  happen on a memory-pressured device; this is not hypothetical.)
- Phone throughput is ~2-3 long docs/s (bge-small, 512-token abstracts, CPU), so full `scifact`
  is ~40 min the first time and instant after. Queries are short and run ~15/s.

## RAG with vs without retrieval — `rag_eval.py`

```bash
# phone embeddings cached first (embeddings server up), then chat from a frontier model
python3 benchmark/analysis/rag_eval.py \
  --embed-endpoint "phone-bge|http://localhost:8080/v1|bge-small|" \
  --embed-endpoint "oai-small|https://api.openai.com/v1|text-embedding-3-small|OPENAI_API_KEY" \
  --chat-endpoint  "gpt|https://api.openai.com/v1|gpt-4o-mini|OPENAI_API_KEY" \
  --limit 500 --top-k 5 --out benchmark/analysis/results/rag.json
```

Output is one `closed-book` row (the "without retrieval" baseline, same for every embedding model)
and one `rag@k` row per `--embed-endpoint`, each with Exact-Match, token-F1 and `ret.hit@k` — how
often the gold passage was in the retrieved top-k. `ret.hit@k` isolates the retriever from the
answerer: a low RAG score with a high hit rate is the chat model's fault, not the embedding's.

To run RAG fully on the phone (embeddings + chat, no frontier): run once with the embeddings server
up so the corpus/query vectors land in the cache, then restart the phone server on `llama.cpp`
(`LFM2….gguf`) and rerun with `--chat-endpoint "phone-lfm2|http://localhost:8080/v1|LFM2.5-1.2B|"`
— the cached embeddings mean `--embed-endpoint` is not called again.

## Frontier keys

`text-embedding-3-{small,large}` and `gpt-4o-mini` need `OPENAI_API_KEY` exported. The harness reads
whatever env var the endpoint's fourth field names, so a different provider's OpenAI-compatible
endpoint (its own base_url + key env) drops in the same way.

## Metrics

- **nDCG@10** — ranking quality with graded relevance, discounted by position; the headline BEIR
  metric.
- **Recall@10 / @100** — fraction of relevant documents found in the top-10 / top-100.
- **MRR@10** — 1/rank of the first relevant document.
- **Exact-Match / token-F1** — SQuAD's official answer scoring, normalised for case, articles and
  punctuation.
