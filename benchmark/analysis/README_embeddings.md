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

## Serving the phone

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
