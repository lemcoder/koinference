#!/usr/bin/env python3
"""RAG "with vs without retrieval" answer-accuracy benchmark, OpenAI-compatible end to end.

    # phone embeddings + phone chat model, both over adb forward
    python3 benchmark/analysis/rag_eval.py \
        --embed-endpoint "phone-bge|http://localhost:8080/v1|bge-small|" \
        --chat-endpoint  "phone-lfm2|http://localhost:8080/v1|LFM2.5-1.2B|" \
        --limit 200 --top-k 3 --out results/rag

    # phone embeddings, frontier chat, frontier embeddings for the comparison
    python3 benchmark/analysis/rag_eval.py \
        --embed-endpoint "phone-bge|http://localhost:8080/v1|bge-small|" \
        --embed-endpoint "oai-small|https://api.openai.com/v1|text-embedding-3-small|OPENAI_API_KEY" \
        --chat-endpoint  "gpt|https://api.openai.com/v1|gpt-4o-mini|OPENAI_API_KEY" \
        --limit 500 --top-k 5

The question this answers: does retrieval actually help a model answer, and how much does the
*embedding model* driving retrieval matter. It runs SQuAD v2 as an open-book retrieval task --
every passage in the split becomes one corpus, and a question is answered twice:

  closed-book  -- the chat model alone, no context
  rag          -- the top-k passages retrieved by an embedding model, put in the prompt

Both answers are scored against SQuAD's gold answers with the official Exact-Match and token-F1,
so the numbers are deterministic and need no judge. Sweeping several --embed-endpoint values shows
how retrieval quality (phone bge-small vs text-embedding-3) moves the RAG score for one fixed chat
model; the closed-book column is the same for all of them and is the "without retrieval" baseline.

SQuAD because it is self-contained: one 4 MB JSON with gold short answers, and pooling its passages
into a corpus turns "answer this" into "find the passage, then answer" -- exactly the retrieval
step RAG adds. Retrieval and embedding reuse embed_eval.py; the chat call is the only new HTTP.

Stdlib only, same as embed_eval.py.
"""

from __future__ import annotations

import argparse
import collections
import json
import pathlib
import re
import string
import sys
import urllib.request

import embed_eval  # Endpoint, embed_all, l2_normalise, rank

SQUAD_DEV_URL = "https://rajpurkar.github.io/SQuAD-explorer/dataset/dev-v2.0.json"


# ── dataset ──────────────────────────────────────────────────────────────────

def load_squad(data_dir: pathlib.Path, limit: int | None) -> tuple[dict[str, str], list[dict]]:
    """Return (corpus: passage_id -> text, qas: [{id, question, answers, gold_passage}]).

    Only answerable questions are kept (SQuAD v2's is_impossible ones have no gold span, so
    "without retrieval" and "with" are both scored against an empty answer and tell us nothing about
    retrieval). Every distinct passage is a corpus document, so the retriever ranks the real passage
    against every other one in the split.
    """
    data_dir.mkdir(parents=True, exist_ok=True)
    path = data_dir / "squad-dev-v2.0.json"
    if not path.exists():
        print(f"  downloading {SQUAD_DEV_URL}", file=sys.stderr)
        urllib.request.urlretrieve(SQUAD_DEV_URL, path)

    raw = json.loads(path.read_text(encoding="utf-8"))
    corpus: dict[str, str] = {}
    qas: list[dict] = []
    for article in raw["data"]:
        for pi, para in enumerate(article["paragraphs"]):
            passage_id = f"{article['title']}::{pi}"
            corpus[passage_id] = para["context"]
            for qa in para["qas"]:
                if qa.get("is_impossible"):
                    continue
                answers = list({a["text"] for a in qa["answers"] if a["text"].strip()})
                if not answers:
                    continue
                qas.append({
                    "id": qa["id"],
                    "question": qa["question"],
                    "answers": answers,
                    "gold_passage": passage_id,
                })
    if limit is not None:
        qas = qas[:limit]
    # Keep the whole passage corpus regardless of the question limit: retrieval has to rank against
    # all of it, not only the passages the sampled questions came from.
    return corpus, qas


# ── SQuAD official scoring (normalise, EM, token-F1) ─────────────────────────

def normalise(text: str) -> str:
    text = text.lower()
    text = "".join(ch for ch in text if ch not in set(string.punctuation))
    text = re.sub(r"\b(a|an|the)\b", " ", text)
    return " ".join(text.split())


def exact_match(prediction: str, golds: list[str]) -> float:
    return float(any(normalise(prediction) == normalise(g) for g in golds))


def token_f1(prediction: str, golds: list[str]) -> float:
    best = 0.0
    pred_tokens = normalise(prediction).split()
    for gold in golds:
        gold_tokens = normalise(gold).split()
        common = collections.Counter(pred_tokens) & collections.Counter(gold_tokens)
        overlap = sum(common.values())
        if overlap == 0:
            continue
        precision = overlap / len(pred_tokens)
        recall = overlap / len(gold_tokens)
        best = max(best, 2 * precision * recall / (precision + recall))
    return best


# ── chat ─────────────────────────────────────────────────────────────────────

CLOSED_SYSTEM = ("Answer the question with the shortest possible span -- a name, date or phrase, "
                 "not a sentence. If you do not know, answer with your best guess anyway.")
RAG_SYSTEM = ("Answer the question using only the context passages. Reply with the shortest "
              "possible span copied from the context -- a name, date or phrase, not a sentence. "
              "If the context does not contain the answer, reply with your best guess from it.")


def chat(endpoint: embed_eval.Endpoint, system: str, user: str, timeout: float) -> str:
    payload = json.dumps({
        "model": endpoint.model,
        "messages": [{"role": "system", "content": system},
                     {"role": "user", "content": user}],
        "temperature": 0.0,
        "max_tokens": 64,
        "stream": False,
    }).encode("utf-8")
    request = urllib.request.Request(f"{endpoint.base_url}/chat/completions",
                                     data=payload, method="POST")
    request.add_header("Content-Type", "application/json")
    if endpoint.api_key:
        request.add_header("Authorization", f"Bearer {endpoint.api_key}")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = json.loads(response.read().decode("utf-8"))
    return body["choices"][0]["message"]["content"].strip()


def rag_prompt(question: str, passages: list[str]) -> str:
    context = "\n\n".join(f"[{i + 1}] {p}" for i, p in enumerate(passages))
    return f"Context:\n{context}\n\nQuestion: {question}"


# ── run ──────────────────────────────────────────────────────────────────────

def run(embed_endpoints: list[embed_eval.Endpoint], chat_endpoint: embed_eval.Endpoint,
        corpus: dict[str, str], qas: list[dict], *, top_k: int, batch_size: int, timeout: float,
        cache: pathlib.Path | None) -> list[dict]:
    corpus_ids = list(corpus)
    question_texts = [qa["question"] for qa in qas]

    # closed-book once: it does not depend on the embedding model.
    print(f"  closed-book: {len(qas)} questions via {chat_endpoint.label}", file=sys.stderr)
    closed_em = closed_f1 = 0.0
    for i, qa in enumerate(qas):
        answer = chat(chat_endpoint, CLOSED_SYSTEM, qa["question"], timeout)
        closed_em += exact_match(answer, qa["answers"])
        closed_f1 += token_f1(answer, qa["answers"])
        print(f"\r    {i + 1}/{len(qas)}", end="", file=sys.stderr)
    print(file=sys.stderr)
    n = len(qas)
    results = [{
        "embed_endpoint": "(none)", "embed_model": "-", "chat_endpoint": chat_endpoint.label,
        "chat_model": chat_endpoint.model, "condition": "closed-book", "top_k": 0,
        "questions": n, "exact_match": closed_em / n, "token_f1": closed_f1 / n,
        "retrieval_hit_rate": None,
    }]

    for embed_endpoint in embed_endpoints:
        print(f"  rag[{embed_endpoint.label}]: embedding corpus + questions", file=sys.stderr)
        corpus_vecs = embed_eval.embed_all(
            embed_endpoint, corpus_ids, [corpus[d] for d in corpus_ids],
            batch_size=batch_size, timeout=timeout, cache=cache, tag="squad.corpus")
        query_vecs = embed_eval.embed_all(
            embed_endpoint, [qa["id"] for qa in qas], question_texts,
            batch_size=batch_size, timeout=timeout, cache=cache, tag="squad.queries")

        em = f1 = 0.0
        hits = 0
        print(f"  rag[{embed_endpoint.label}]: answering", file=sys.stderr)
        for i, qa in enumerate(qas):
            ranked = embed_eval.rank(query_vecs[qa["id"]], corpus_vecs, top_k=top_k)
            hits += qa["gold_passage"] in ranked
            passages = [corpus[did] for did in ranked]
            answer = chat(chat_endpoint, RAG_SYSTEM, rag_prompt(qa["question"], passages), timeout)
            em += exact_match(answer, qa["answers"])
            f1 += token_f1(answer, qa["answers"])
            print(f"\r    {i + 1}/{len(qas)}", end="", file=sys.stderr)
        print(file=sys.stderr)
        results.append({
            "embed_endpoint": embed_endpoint.label, "embed_model": embed_endpoint.model,
            "chat_endpoint": chat_endpoint.label, "chat_model": chat_endpoint.model,
            "condition": "rag", "top_k": top_k, "questions": n,
            "exact_match": em / n, "token_f1": f1 / n, "retrieval_hit_rate": hits / n,
        })
    return results


def print_table(results: list[dict]) -> None:
    print(f"\nchat model: {results[0]['chat_model']}   questions: {results[0]['questions']}")
    header = (f"  {'condition':<12}{'embed model':<26}{'EM':>8}{'F1':>8}"
              f"{'ret.hit@k':>11}")
    print(header)
    print("  " + "-" * (len(header) - 2))
    for row in results:
        hit = "-" if row["retrieval_hit_rate"] is None else f"{row['retrieval_hit_rate']:.3f}"
        label = row["condition"] + (f"@{row['top_k']}" if row["condition"] == "rag" else "")
        print(f"  {label:<12}{row['embed_model']:<26}{row['exact_match']:>8.3f}"
              f"{row['token_f1']:>8.3f}{hit:>11}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--embed-endpoint", action="append", required=True, dest="embed_endpoints",
                        help="'label|base_url|model|api_key_env', repeatable (one RAG row each)")
    parser.add_argument("--chat-endpoint", required=True,
                        help="'label|base_url|model|api_key_env' -- the answering model, fixed")
    parser.add_argument("--data-dir", type=pathlib.Path,
                        default=pathlib.Path("benchmark/analysis/.squad"))
    parser.add_argument("--cache-dir", type=pathlib.Path,
                        default=pathlib.Path("benchmark/analysis/.embcache"))
    parser.add_argument("--no-cache", action="store_true")
    parser.add_argument("--limit", type=int, default=200, help="number of questions (SQuAD dev)")
    parser.add_argument("--top-k", type=int, default=3)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--out", type=pathlib.Path, default=None)
    args = parser.parse_args()

    embed_endpoints = [embed_eval.Endpoint.parse(s) for s in args.embed_endpoints]
    chat_endpoint = embed_eval.Endpoint.parse(args.chat_endpoint)
    cache = None if args.no_cache else args.cache_dir

    corpus, qas = load_squad(args.data_dir, limit=args.limit)
    print(f"  corpus {len(corpus)} passages, {len(qas)} answerable questions", file=sys.stderr)

    results = run(embed_endpoints, chat_endpoint, corpus, qas,
                  top_k=args.top_k, batch_size=args.batch_size, timeout=args.timeout, cache=cache)
    print_table(results)

    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(results, indent=2))
        print(f"\nwrote {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
