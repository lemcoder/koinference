#!/usr/bin/env python3
"""Retrieval-quality benchmark for an OpenAI-compatible /v1/embeddings endpoint.

    # phone (bge-small), reached over adb forward tcp:8080 tcp:8080
    python3 benchmark/analysis/embed_eval.py \
        --dataset scifact,nfcorpus \
        --endpoint "phone-bge|http://localhost:8080/v1|bge-small|" \
        --endpoint "oai-small|https://api.openai.com/v1|text-embedding-3-small|OPENAI_API_KEY" \
        --endpoint "oai-large|https://api.openai.com/v1|text-embedding-3-large|OPENAI_API_KEY" \
        --out results/embeddings

What it measures: how well an embedding model *ranks the right document for a query*, which is
the thing that decides whether RAG finds its source. It runs BEIR datasets (corpus + queries +
human relevance judgements), embeds both sides, ranks every corpus document for every query by
cosine, and scores the ranking against the judgements with nDCG@10, Recall@10, Recall@100 and
MRR@10 -- the same metrics the BEIR leaderboard reports, so a phone's bge-small lands on the same
axis as text-embedding-3.

Any OpenAI-compatible endpoint works by URL: the phone's Ktor server and OpenAI's API take the
same request. An endpoint is `label|base_url|model|api_key_env`, repeatable; the api_key_env names
an environment variable (empty for the phone, which has no auth).

Stdlib only, on purpose -- same reason openai_bench.py is: the machine measuring a phone should not
need a dependency tree. Vector maths is pure Python, which is fine at BEIR-subset sizes because the
run is dominated by embedding over HTTP, not by ranking. Corpus embeddings are cached to disk, so a
second run against the same (dataset, model) re-ranks in seconds and never re-embeds.

For the RAG "with vs without retrieval" answer-accuracy comparison, see rag_eval.py, which reuses
this file's retrieval and dataset loading.
"""

from __future__ import annotations

import argparse
import base64
import json
import math
import os
import pathlib
import struct
import sys
import time
import urllib.error
import urllib.request
import zipfile
from dataclasses import dataclass

# BEIR's public mirror. Each dataset is a zip of corpus.jsonl, queries.jsonl and qrels/test.tsv.
BEIR_BASE = "https://public.ukp.informatik.tu-darmstadt.de/thakur/BEIR/datasets"

# Sizes are the reason these four are the default subset: all fit on a phone in minutes. Corpus
# document counts (approx): scifact 5k, nfcorpus 3.6k, fiqa 57k, trec-covid 171k. The last two are
# opt-in via --dataset because embedding 171k documents over adb is an afternoon, not a coffee.
KNOWN_DATASETS = ("scifact", "nfcorpus", "fiqa", "trec-covid", "arguana", "scidocs")


# ── dataset loading ──────────────────────────────────────────────────────────

@dataclass
class Dataset:
    name: str
    corpus: dict[str, str]            # doc_id -> "title. text"
    queries: dict[str, str]           # query_id -> text
    qrels: dict[str, dict[str, int]]  # query_id -> {doc_id: relevance}


def download_beir(name: str, data_dir: pathlib.Path) -> pathlib.Path:
    """Fetch and unzip one BEIR dataset, cached under data_dir. Returns its extracted folder."""
    target = data_dir / name
    if (target / "corpus.jsonl").exists():
        return target
    data_dir.mkdir(parents=True, exist_ok=True)
    url = f"{BEIR_BASE}/{name}.zip"
    zip_path = data_dir / f"{name}.zip"
    print(f"  downloading {url}", file=sys.stderr)
    try:
        urllib.request.urlretrieve(url, zip_path)
    except urllib.error.HTTPError as exc:
        raise SystemExit(f"Could not fetch BEIR dataset {name!r} from {url}: {exc}")
    with zipfile.ZipFile(zip_path) as archive:
        archive.extractall(data_dir)
    zip_path.unlink(missing_ok=True)
    # The zip extracts to data_dir/<name>/; some mirrors nest one level deeper.
    if not (target / "corpus.jsonl").exists():
        raise SystemExit(f"Unexpected BEIR layout for {name}: no corpus.jsonl under {target}")
    return target


def load_dataset(name: str, data_dir: pathlib.Path, split: str = "test",
                 max_corpus: int | None = None) -> Dataset:
    folder = download_beir(name, data_dir)

    qrels: dict[str, dict[str, int]] = {}
    qrels_path = folder / "qrels" / f"{split}.tsv"
    with qrels_path.open(encoding="utf-8") as handle:
        header = next(handle)  # query-id\tcorpus-id\tscore
        if not header.lower().startswith("query"):
            handle.seek(0)  # some mirrors ship headerless qrels
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 3:
                continue
            qid, did, score = parts
            qrels.setdefault(qid, {})[did] = int(score)

    # Only queries with at least one judgement are scorable; keep just those, and only the corpus
    # documents referenced plus everything else (retrieval must rank distractors, so keep the whole
    # corpus unless --max-corpus caps it -- but never drop a judged document).
    judged_docs = {d for rels in qrels.values() for d in rels}

    corpus: dict[str, str] = {}
    with (folder / "corpus.jsonl").open(encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            did = row["_id"]
            title = (row.get("title") or "").strip()
            text = (row.get("text") or "").strip()
            corpus[did] = f"{title}. {text}".strip(". ") if title else text
    if max_corpus is not None and len(corpus) > max_corpus:
        # Keep every judged document, then fill up to max_corpus with distractors in file order.
        kept = dict.fromkeys(judged_docs)
        for did, txt in corpus.items():
            if len(kept) >= max_corpus and did not in judged_docs:
                continue
            kept[did] = None
        corpus = {did: corpus[did] for did in kept if did in corpus}

    queries: dict[str, str] = {}
    with (folder / "queries.jsonl").open(encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            if row["_id"] in qrels:
                queries[row["_id"]] = (row.get("text") or "").strip()

    return Dataset(name=name, corpus=corpus, queries=queries, qrels=qrels)


# ── embedding client (OpenAI-compatible, stdlib) ─────────────────────────────

@dataclass
class Endpoint:
    label: str
    base_url: str
    model: str
    api_key_env: str

    @classmethod
    def parse(cls, spec: str) -> "Endpoint":
        parts = spec.split("|")
        if len(parts) != 4:
            raise SystemExit(
                f"--endpoint must be 'label|base_url|model|api_key_env', got {spec!r}"
            )
        label, base_url, model, key_env = (p.strip() for p in parts)
        return cls(label=label, base_url=base_url.rstrip("/"), model=model, api_key_env=key_env)

    @property
    def api_key(self) -> str | None:
        if not self.api_key_env:
            return None
        key = os.environ.get(self.api_key_env)
        if not key:
            raise SystemExit(
                f"Endpoint {self.label!r} names api key env {self.api_key_env!r}, which is unset"
            )
        return key


def embed_batch(endpoint: Endpoint, texts: list[str], timeout: float) -> list[list[float]]:
    """One /v1/embeddings call. Requests base64 (little-endian float32), which both the phone's
    Ktor server and OpenAI support and which halves the bytes on the wire versus a JSON array."""
    payload = json.dumps({
        "model": endpoint.model,
        "input": texts,
        "encoding_format": "base64",
    }).encode("utf-8")
    request = urllib.request.Request(
        f"{endpoint.base_url}/embeddings", data=payload, method="POST"
    )
    request.add_header("Content-Type", "application/json")
    if endpoint.api_key:
        request.add_header("Authorization", f"Bearer {endpoint.api_key}")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = json.loads(response.read().decode("utf-8"))
    # data may come back out of order in principle; sort by index to be safe.
    rows = sorted(body["data"], key=lambda d: d["index"])
    out = []
    for row in rows:
        emb = row["embedding"]
        if isinstance(emb, str):
            raw = base64.b64decode(emb)
            out.append(list(struct.unpack(f"<{len(raw) // 4}f", raw)))
        else:
            out.append([float(x) for x in emb])
    return out


def l2_normalise(vec: list[float]) -> list[float]:
    norm = math.sqrt(sum(x * x for x in vec))
    return [x / norm for x in vec] if norm > 0 else vec


def embed_all(endpoint: Endpoint, ids: list[str], texts: list[str], *, batch_size: int,
              timeout: float, cache: pathlib.Path | None, tag: str) -> dict[str, list[float]]:
    """Embed every text, batched, L2-normalised, cached by (tag, endpoint, id-set) to disk.

    The cache is keyed on the exact id order so a changed corpus never silently reuses stale
    vectors; a mismatch just re-embeds. Vectors are stored raw little-endian float32 next to a
    JSON id list, because 5k*384 floats as JSON is 40 MB and as f32 is 7.5.
    """
    if cache is not None:
        vecs = _cache_load(cache, tag, endpoint, ids)
        if vecs is not None:
            print(f"    [{endpoint.label}] {tag}: {len(ids)} cached", file=sys.stderr)
            return vecs

    result: dict[str, list[float]] = {}
    start = time.perf_counter()
    for i in range(0, len(texts), batch_size):
        chunk_ids = ids[i:i + batch_size]
        chunk_txt = texts[i:i + batch_size]
        vecs = embed_batch(endpoint, chunk_txt, timeout)
        for did, vec in zip(chunk_ids, vecs):
            result[did] = l2_normalise(vec)
        done = min(i + batch_size, len(texts))
        print(f"\r    [{endpoint.label}] {tag}: {done}/{len(texts)} "
              f"({done / (time.perf_counter() - start):.0f}/s)", end="", file=sys.stderr)
    print(file=sys.stderr)
    if cache is not None:
        _cache_store(cache, tag, endpoint, ids, result)
    return result


def _cache_paths(cache: pathlib.Path, tag: str, endpoint: Endpoint) -> tuple[pathlib.Path, pathlib.Path]:
    safe = f"{tag}.{endpoint.label}.{endpoint.model}".replace("/", "_")
    return cache / f"{safe}.ids.json", cache / f"{safe}.f32"


def _cache_load(cache: pathlib.Path, tag: str, endpoint: Endpoint,
                ids: list[str]) -> dict[str, list[float]] | None:
    id_path, vec_path = _cache_paths(cache, tag, endpoint)
    if not (id_path.exists() and vec_path.exists()):
        return None
    stored_ids = json.loads(id_path.read_text())
    if stored_ids != ids:
        return None
    raw = vec_path.read_bytes()
    floats = struct.unpack(f"<{len(raw) // 4}f", raw)
    dim = len(floats) // len(ids)
    return {did: list(floats[k * dim:(k + 1) * dim]) for k, did in enumerate(ids)}


def _cache_store(cache: pathlib.Path, tag: str, endpoint: Endpoint, ids: list[str],
                 vecs: dict[str, list[float]]) -> None:
    cache.mkdir(parents=True, exist_ok=True)
    id_path, vec_path = _cache_paths(cache, tag, endpoint)
    id_path.write_text(json.dumps(ids))
    flat = bytearray()
    for did in ids:
        flat += struct.pack(f"<{len(vecs[did])}f", *vecs[did])
    vec_path.write_bytes(bytes(flat))


# ── ranking + metrics ────────────────────────────────────────────────────────

def rank(query_vec: list[float], corpus_vecs: dict[str, list[float]], top_k: int) -> list[str]:
    """Top-k corpus ids by cosine. Vectors are already L2-normalised, so cosine is the dot product.
    Pure-Python and O(|corpus| * dim) per query -- fine at BEIR-subset sizes; the HTTP embedding
    above is the real cost."""
    scores = []
    for did, vec in corpus_vecs.items():
        scores.append((sum(a * b for a, b in zip(query_vec, vec)), did))
    scores.sort(reverse=True)
    return [did for _, did in scores[:top_k]]


def dcg(relevances: list[int]) -> float:
    return sum(rel / math.log2(i + 2) for i, rel in enumerate(relevances))


def score_query(ranked: list[str], rels: dict[str, int]) -> dict[str, float]:
    total_relevant = sum(1 for r in rels.values() if r > 0)
    if total_relevant == 0:
        return {}
    gains = [rels.get(did, 0) for did in ranked]

    ndcg10 = 0.0
    ideal = dcg(sorted(rels.values(), reverse=True)[:10])
    if ideal > 0:
        ndcg10 = dcg(gains[:10]) / ideal

    recall10 = sum(1 for did in ranked[:10] if rels.get(did, 0) > 0) / total_relevant
    recall100 = sum(1 for did in ranked[:100] if rels.get(did, 0) > 0) / total_relevant

    mrr10 = 0.0
    for i, did in enumerate(ranked[:10]):
        if rels.get(did, 0) > 0:
            mrr10 = 1.0 / (i + 1)
            break

    return {"ndcg@10": ndcg10, "recall@10": recall10, "recall@100": recall100, "mrr@10": mrr10}


def evaluate(endpoint: Endpoint, dataset: Dataset, *, batch_size: int, timeout: float,
             cache: pathlib.Path | None) -> dict:
    corpus_ids = list(dataset.corpus)
    corpus_vecs = embed_all(endpoint, corpus_ids, [dataset.corpus[d] for d in corpus_ids],
                            batch_size=batch_size, timeout=timeout, cache=cache,
                            tag=f"{dataset.name}.corpus")
    query_ids = list(dataset.queries)
    query_vecs = embed_all(endpoint, query_ids, [dataset.queries[q] for q in query_ids],
                           batch_size=batch_size, timeout=timeout, cache=cache,
                           tag=f"{dataset.name}.queries")

    dim = len(next(iter(corpus_vecs.values())))
    totals: dict[str, float] = {}
    scored = 0
    for qid in query_ids:
        ranked = rank(query_vecs[qid], corpus_vecs, top_k=100)
        metrics = score_query(ranked, dataset.qrels.get(qid, {}))
        if not metrics:
            continue
        scored += 1
        for key, value in metrics.items():
            totals[key] = totals.get(key, 0.0) + value

    averaged = {key: value / scored for key, value in totals.items()} if scored else {}
    return {
        "endpoint": endpoint.label,
        "model": endpoint.model,
        "dataset": dataset.name,
        "dimensions": dim,
        "corpus_size": len(corpus_ids),
        "queries_scored": scored,
        "metrics": averaged,
    }


# ── reporting ────────────────────────────────────────────────────────────────

METRIC_ORDER = ("ndcg@10", "recall@10", "recall@100", "mrr@10")


def print_table(results: list[dict]) -> None:
    by_dataset: dict[str, list[dict]] = {}
    for row in results:
        by_dataset.setdefault(row["dataset"], []).append(row)
    for dataset, rows in by_dataset.items():
        print(f"\n{dataset}  (corpus {rows[0]['corpus_size']}, queries {rows[0]['queries_scored']})")
        header = f"  {'endpoint':<16}{'dim':>6}  " + "".join(f"{m:>12}" for m in METRIC_ORDER)
        print(header)
        print("  " + "-" * (len(header) - 2))
        for row in rows:
            cells = "".join(f"{row['metrics'].get(m, 0.0):>12.4f}" for m in METRIC_ORDER)
            print(f"  {row['endpoint']:<16}{row['dimensions']:>6}  {cells}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", default="scifact",
                        help=f"comma-separated BEIR datasets. Known small ones: {KNOWN_DATASETS}")
    parser.add_argument("--endpoint", action="append", required=True, dest="endpoints",
                        help="'label|base_url|model|api_key_env', repeatable")
    parser.add_argument("--data-dir", type=pathlib.Path,
                        default=pathlib.Path("benchmark/analysis/.beir"),
                        help="where BEIR datasets are downloaded/cached")
    parser.add_argument("--cache-dir", type=pathlib.Path,
                        default=pathlib.Path("benchmark/analysis/.embcache"),
                        help="where corpus/query embeddings are cached (per dataset+model)")
    parser.add_argument("--no-cache", action="store_true", help="disable the embedding cache")
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--max-corpus", type=int, default=None,
                        help="cap corpus size (keeps every judged doc); use it to keep big "
                             "datasets phone-sized")
    parser.add_argument("--split", default="test")
    parser.add_argument("--out", type=pathlib.Path, default=None,
                        help="write the full results JSON to this path (a .json file)")
    args = parser.parse_args()

    endpoints = [Endpoint.parse(spec) for spec in args.endpoints]
    cache = None if args.no_cache else args.cache_dir

    results: list[dict] = []
    for name in [d.strip() for d in args.dataset.split(",") if d.strip()]:
        print(f"\n=== {name} ===", file=sys.stderr)
        dataset = load_dataset(name, args.data_dir, split=args.split, max_corpus=args.max_corpus)
        print(f"  corpus {len(dataset.corpus)}, queries {len(dataset.queries)}", file=sys.stderr)
        for endpoint in endpoints:
            result = evaluate(endpoint, dataset, batch_size=args.batch_size,
                              timeout=args.timeout, cache=cache)
            results.append(result)

    print_table(results)

    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(results, indent=2))
        print(f"\nwrote {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
