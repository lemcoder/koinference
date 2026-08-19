#!/usr/bin/env python3
"""Benchmark a model served by the Android app over its OpenAI-compatible API.

    python3 benchmark/analysis/openai_bench.py http://192.168.1.42:8080 \
        --prompt-id short_generation_v1 --iterations 5 --out results/raw

Measures from the client, which is where a client's latency actually is: the interval from
sending the request to the first SSE chunk arriving, and the interval from there to the last.
Those numbers include HTTP, serialisation and the network, and are therefore *not* the same
measurement the on-device harness makes — each record says so, and the analysis tool keeps
them apart by device label.

Writes the same JSON schema benchmark/core emits, so analyze_results.py reads both without
knowing which produced what.

Only depends on the standard library: a benchmark client that needs a dependency tree is one
more thing to install on a machine that is meant to be measuring a phone.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import statistics
import sys
import time
import urllib.error
import urllib.request

BENCHMARK_VERSION = "1"


def get_json(base_url: str, path: str, timeout: float = 10.0):
    with urllib.request.urlopen(f"{base_url}{path}", timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def load_prompt(prompt_id: str, fixtures: pathlib.Path) -> dict:
    corpus = json.loads(fixtures.read_text(encoding="utf-8"))
    for prompt in corpus["prompts"]:
        if prompt["id"] == prompt_id:
            return prompt
    raise SystemExit(
        f"No prompt {prompt_id!r} in {fixtures}. Known: {[p['id'] for p in corpus['prompts']]}"
    )


def stream_completion(base_url: str, model: str, prompt: str, max_tokens: int, timeout: float):
    """One streamed completion, timed.

    time.perf_counter() throughout: monotonic, and unaffected by the clock being adjusted
    mid-run. The first-chunk stamp is taken before the payload is parsed, so JSON decoding on
    this machine does not land inside the measurement.
    """
    payload = json.dumps(
        {
            "model": model,
            "messages": [{"role": "user", "content": prompt}],
            "stream": True,
            "max_tokens": max_tokens,
        }
    ).encode("utf-8")

    request = urllib.request.Request(
        f"{base_url}/v1/chat/completions",
        data=payload,
        headers={"Content-Type": "application/json", "Accept": "text/event-stream"},
    )

    started = time.perf_counter()
    first_chunk_at = None
    chunks = 0
    text: list[str] = []

    with urllib.request.urlopen(request, timeout=timeout) as response:
        for raw_line in response:
            line = raw_line.decode("utf-8").strip()
            if not line.startswith("data:"):
                continue
            data = line[len("data:"):].strip()
            if data == "[DONE]":
                break

            arrived = time.perf_counter()
            event = json.loads(data)
            delta = (event.get("choices") or [{}])[0].get("delta") or {}
            content = delta.get("content")
            if not content:
                continue

            if first_chunk_at is None:
                first_chunk_at = arrived
            chunks += 1
            text.append(content)

    ended = time.perf_counter()
    return {
        "wallClockMs": (ended - started) * 1000.0,
        "ttftMs": None if first_chunk_at is None else (first_chunk_at - started) * 1000.0,
        "streamingMs": None if first_chunk_at is None else (ended - first_chunk_at) * 1000.0,
        "chunks": chunks,
        "text": "".join(text),
    }


def to_sample(iteration: int, measurement: dict, peak_pss_kb: int | None) -> dict:
    streaming_ms = measurement["streamingMs"]
    chunks = measurement["chunks"]
    # chunks - 1, matching the Kotlin harness: the first chunk arrived before this interval.
    chunks_per_second = (
        (chunks - 1) * 1000.0 / streaming_ms
        if streaming_ms and streaming_ms > 0 and chunks > 1
        else None
    )
    return {
        "iteration": iteration,
        "wallClockMs": measurement["wallClockMs"],
        "ttftMs": measurement["ttftMs"],
        "streamingMs": streaming_ms,
        "chunks": chunks,
        "chunksPerSecond": chunks_per_second,
        "outputChars": len(measurement["text"]),
        "peakPssKb": peak_pss_kb,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("base_url", help="e.g. http://192.168.1.42:8080")
    parser.add_argument("--prompt-id", default="short_generation_v1")
    parser.add_argument(
        "--fixtures",
        type=pathlib.Path,
        default=pathlib.Path(__file__).resolve().parents[1] / "fixtures" / "prompts.json",
    )
    parser.add_argument("--iterations", type=int, default=5)
    parser.add_argument("--warmup", type=int, default=1)
    parser.add_argument("--max-tokens", type=int, default=128)
    parser.add_argument("--timeout", type=float, default=600.0)
    parser.add_argument("--run-id", default=None)
    parser.add_argument("--out", type=pathlib.Path, default=None, help="directory for the JSON")
    args = parser.parse_args()

    base_url = args.base_url.rstrip("/")

    try:
        models = get_json(base_url, "/v1/models")
        device = get_json(base_url, "/koinference/device")
        memory_before = get_json(base_url, "/koinference/memory")
    except (urllib.error.URLError, TimeoutError) as exc:
        print(f"Cannot reach {base_url}: {exc}", file=sys.stderr)
        print("Is the service running? adb shell am start-foreground-service ...", file=sys.stderr)
        return 2

    card = models["data"][0]
    model_id, engine = card["id"], card["koinference_engine"]
    prompt = load_prompt(args.prompt_id, args.fixtures)
    run_id = args.run_id or f"http-{int(time.time())}"

    print(f"model:  {model_id} on {engine}")
    print(f"device: {device.get('manufacturer')} {device.get('model')} (API {device.get('sdk')})")
    print(f"prompt: {prompt['id']} ({len(prompt['text'])} chars), {args.iterations} iterations")

    warmups, samples = [], []
    peak_pss = memory_before.get("pssKb")

    for index in range(args.warmup + args.iterations):
        measurement = stream_completion(
            base_url, model_id, prompt["text"], args.max_tokens, args.timeout
        )
        memory = get_json(base_url, "/koinference/memory")
        pss = memory.get("pssKb")
        if pss is not None:
            peak_pss = max(peak_pss or pss, pss)

        warming = index < args.warmup
        sample = to_sample(index - args.warmup if not warming else index, measurement, pss)
        (warmups if warming else samples).append(sample)

        label = "warmup" if warming else "sample"
        ttft = sample["ttftMs"]
        ttft_text = "n/a" if ttft is None else f"{ttft:.1f}ms"
        print(
            f"  {label} {sample['iteration']}: ttft={ttft_text} "
            f"total={sample['wallClockMs']:.0f}ms chunks={sample['chunks']} "
            f"chars={sample['outputChars']}"
        )

    memory_after = get_json(base_url, "/koinference/memory")

    record = {
        "engine": {
            "id": engine,
            "modelId": model_id,
            "modelVersion": "unknown",
            # Not inferable from the served model, and inventing it would make two runs look
            # comparable when they are not. Pass it through the run's metadata instead.
            "quantization": "unknown",
            "modelSha256": None,
        },
        "workload": {
            "promptId": prompt["id"],
            "promptSha256": prompt.get("sha256"),
            "promptChars": len(prompt["text"]),
            "maxNewTokens": args.max_tokens,
        },
        "status": "SUCCESS" if samples else "FAILED",
        "failureReason": None if samples else "no samples were collected",
        "initialization": {
            "processStartMs": None,
            "modelLoadMs": memory_before.get("modelLoadMs"),
            "tokenizerInitMs": None,
        },
        "samples": samples,
        "warmupSamples": warmups,
        "memory": {
            "beforeInitPssKb": None,
            "afterLoadPssKb": memory_before.get("pssKb"),
            "afterWarmupPssKb": None,
            "peakPssKb": peak_pss,
            "afterRunPssKb": memory_after.get("pssKb"),
            "nativeHeapKb": memory_after.get("nativeHeapKb"),
            "javaHeapKb": memory_after.get("javaHeapKb"),
            "rssKb": memory_after.get("rssKb"),
        },
        "thermal": None,
        "battery": None,
        "sustained": None,
        "engineMetadata": {
            "transport": "http",
            "servedFrom": base_url,
            "servicePid": str(memory_after.get("pid")),
            "serviceProcess": str(memory_after.get("processName")),
        },
        "notes": [
            "Measured from an HTTP client: latency includes serialisation, the network and the "
            "server's own overhead, so these numbers are not directly comparable with an "
            "on-device harness run of the same model.",
            "Chunks are SSE events, not tokens.",
            "Memory is read from inside the inference process, which runs alone — see "
            "android:process=\":inference\".",
        ],
    }

    payload = {
        "benchmarkVersion": BENCHMARK_VERSION,
        "runId": run_id,
        # The device as the service reports it, so this file identifies its hardware the same
        # way an on-device run does. hostPlatform marks it as measured over the wire.
        "device": {**device, "hostPlatform": "http-client"},
        "records": [record],
    }

    if samples:
        ttfts = [s["ttftMs"] for s in samples if s["ttftMs"] is not None]
        if ttfts:
            print(
                f"\nttft: median {statistics.median(ttfts):.1f}ms  "
                f"min {min(ttfts):.1f}ms  max {max(ttfts):.1f}ms"
            )

    if args.out:
        args.out.mkdir(parents=True, exist_ok=True)
        target = args.out / f"{run_id}-{engine.replace('.', '_')}-{prompt['id']}.json"
        target.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        print(f"wrote {target}")
        print(f"analyse: python3 benchmark/analysis/analyze_results.py {args.out.parent}")
    else:
        json.dump(payload, sys.stdout, indent=2)
        print()

    return 0 if samples else 1


if __name__ == "__main__":
    raise SystemExit(main())
