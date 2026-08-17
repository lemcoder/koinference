#!/usr/bin/env python3
"""Turn benchmark JSON into statistics, CSV, Markdown and charts.

    python3 benchmark/analysis/analyze_results.py results/

Reads every *.json under the directory (recursively), validates it against the schema version
it claims, and writes:

    <out>/csv/         samples.csv, summary.csv
    <out>/markdown/    summary.md
    <out>/charts/      one PNG per metric, when matplotlib is installed

Statistics are computed here rather than on the device on purpose: the device emits raw
samples, so a summary can always be recomputed, and a suspicious mean can always be traced
back to the iterations it came from.

Rules this tool will not bend:

* Records that are not SUCCESS never enter a statistic. They are counted and listed instead.
* A missing metric is missing. Nothing is imputed, and a series with no data produces no bar
  rather than a zero-height one.
* Chunks are emissions, not tokens. Every throughput figure is reported next to the chunk
  count that produced it, because a chunk means whatever the engine decided it means — one
  token for llama.cpp, whatever LiteRT-LM sends for LiteRT-LM. Latency and time to first chunk
  need no such caveat: the harness measures both above the engine, with one clock and one code
  path, so those columns are comparable as they stand.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import pathlib
import statistics
import sys
from collections import defaultdict
from typing import Any, Iterable

SUPPORTED_VERSIONS = {"1"}

# (column, label, "higher is better")
METRICS = [
    ("ttft_ms", "Time to first chunk (ms)", False),
    ("chunks_per_second", "Chunks/sec", True),
    ("wall_clock_ms", "Total latency (ms)", False),
    ("peak_pss_kb", "Peak PSS (KB)", False),
    ("model_load_ms", "Model load (ms)", False),
]

GROUP_KEYS = ["device", "engine", "model", "quantization", "workload"]


class SchemaError(Exception):
    pass


def load_files(root: pathlib.Path) -> tuple[list[dict[str, Any]], list[str]]:
    """Return (files, problems). A file that does not parse is reported, never skipped silently."""
    files: list[dict[str, Any]] = []
    problems: list[str] = []

    paths = sorted(root.rglob("*.json"))
    if not paths:
        problems.append(f"No .json files under {root}")

    for path in paths:
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
            validate(payload, path)
            payload["_path"] = str(path)
            files.append(payload)
        except (json.JSONDecodeError, SchemaError) as exc:
            problems.append(f"{path}: {exc}")

    return files, problems


def validate(payload: dict[str, Any], path: pathlib.Path) -> None:
    version = payload.get("benchmarkVersion")
    if version not in SUPPORTED_VERSIONS:
        raise SchemaError(
            f"benchmarkVersion {version!r} is not one this tool understands "
            f"({sorted(SUPPORTED_VERSIONS)}). Refusing to guess at its shape."
        )
    for field in ("runId", "device", "records"):
        if field not in payload:
            raise SchemaError(f"missing required field {field!r}")
    if not isinstance(payload["records"], list):
        raise SchemaError("records must be a list")
    for index, record in enumerate(payload["records"]):
        for field in ("engine", "workload", "status"):
            if field not in record:
                raise SchemaError(f"record {index} is missing {field!r}")
        if record["status"] not in {"SUCCESS", "FAILED", "SKIPPED"}:
            raise SchemaError(f"record {index} has unknown status {record['status']!r}")


def device_label(device: dict[str, Any]) -> str:
    """Identify hardware, not marketing.

    Prefers the FTL matrix id because that is what a run was requested as, then the SoC, and
    falls back to manufacturer/model last — two phones with the same model name can carry
    different silicon.
    """
    ftl = device.get("ftlModelId")
    if ftl:
        version = device.get("ftlVersion")
        return f"{ftl}@{version}" if version else str(ftl)
    if device.get("hostPlatform"):
        return str(device["hostPlatform"])
    parts = [device.get("manufacturer"), device.get("model")]
    label = " ".join(str(p) for p in parts if p) or "unknown-device"
    soc = device.get("socModel")
    if soc:
        label += f" ({soc})"
    if device.get("isEmulator"):
        label += " [emulator]"
    return label


def flatten(files: Iterable[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """One row per measured iteration, plus one row per non-SUCCESS record."""
    rows: list[dict[str, Any]] = []
    failures: list[dict[str, Any]] = []

    for payload in files:
        device = device_label(payload["device"])
        for record in payload["records"]:
            engine = record["engine"]
            workload = record["workload"]
            base = {
                "run_id": payload["runId"],
                "device": device,
                "soc": payload["device"].get("socModel"),
                "android_sdk": payload["device"].get("sdk"),
                "engine": engine["id"],
                "engine_version": engine.get("version"),
                "model": engine.get("modelId"),
                "model_version": engine.get("modelVersion"),
                "quantization": engine.get("quantization"),
                "model_sha256": engine.get("modelSha256"),
                "workload": workload.get("promptId"),
                "max_new_tokens": workload.get("maxNewTokens"),
                "source_file": payload["_path"],
            }

            if record["status"] != "SUCCESS":
                failures.append(
                    {
                        **base,
                        "status": record["status"],
                        "failure_reason": record.get("failureReason"),
                    }
                )
                continue

            initialization = record.get("initialization") or {}
            memory = record.get("memory") or {}
            thermal = record.get("thermal") or {}

            for sample in record.get("samples", []):
                rows.append(
                    {
                        **base,
                        "iteration": sample.get("iteration"),
                        "wall_clock_ms": sample.get("wallClockMs"),
                        "ttft_ms": sample.get("ttftMs"),
                        "streaming_ms": sample.get("streamingMs"),
                        "chunks": sample.get("chunks"),
                        "chunks_per_second": sample.get("chunksPerSecond"),
                        "output_chars": sample.get("outputChars"),
                        # Per-record values repeated on each sample row so a single CSV can be
                        # grouped without a join.
                        "peak_pss_kb": sample.get("peakPssKb") or memory.get("peakPssKb"),
                        "model_load_ms": initialization.get("modelLoadMs"),
                        "process_start_ms": initialization.get("processStartMs"),
                        "after_load_pss_kb": memory.get("afterLoadPssKb"),
                        "battery_temp_before_c": thermal.get("batteryTemperatureBeforeC"),
                        "battery_temp_after_c": thermal.get("batteryTemperatureAfterC"),
                        "thermal_status_peak": thermal.get("thermalStatusPeak"),
                    }
                )

    return rows, failures


def percentile(values: list[float], fraction: float) -> float:
    """Nearest-rank percentile.

    Deliberately not interpolated: benchmark samples are few, and an interpolated p95 of five
    iterations reports a number that no iteration produced.
    """
    if not values:
        raise ValueError("no values")
    ordered = sorted(values)
    rank = max(1, math.ceil(fraction * len(ordered)))
    return ordered[min(rank, len(ordered)) - 1]


def summarize(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    groups: dict[tuple, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        groups[tuple(row.get(key) for key in GROUP_KEYS)].append(row)

    summary = []
    for key, group in sorted(groups.items(), key=lambda item: [str(k) for k in item[0]]):
        entry = dict(zip(GROUP_KEYS, key))
        entry["samples"] = len(group)
        chunk_counts = {row.get("chunks") for row in group if row.get("chunks")}
        # Chunks are emissions, not tokens. Carried next to every throughput figure so a
        # chunks/sec comparison is read together with how much a chunk was worth.
        entry["chunk_counts"] = "|".join(str(c) for c in sorted(chunk_counts)) if chunk_counts else None

        for column, _label, _higher in METRICS:
            values = [row[column] for row in group if isinstance(row.get(column), (int, float))]
            if not values:
                # Absent, and left absent: an engine that reports no token count has no decode
                # rate, and a zero here would be read as a very slow engine.
                for suffix in ("min", "max", "mean", "median", "p50", "p90", "p95", "stddev"):
                    entry[f"{column}_{suffix}"] = None
                entry[f"{column}_n"] = 0
                continue

            entry[f"{column}_n"] = len(values)
            entry[f"{column}_min"] = min(values)
            entry[f"{column}_max"] = max(values)
            entry[f"{column}_mean"] = statistics.fmean(values)
            entry[f"{column}_median"] = statistics.median(values)
            entry[f"{column}_p50"] = percentile(values, 0.50)
            entry[f"{column}_p90"] = percentile(values, 0.90)
            entry[f"{column}_p95"] = percentile(values, 0.95)
            entry[f"{column}_stddev"] = statistics.stdev(values) if len(values) > 1 else 0.0

        summary.append(entry)

    return summary


def write_csv(path: pathlib.Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    fieldnames: list[str] = []
    for row in rows:
        for key in row:
            if key not in fieldnames:
                fieldnames.append(key)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def format_number(value: Any) -> str:
    if value is None:
        return "n/a"
    if isinstance(value, float):
        return f"{value:,.1f}" if abs(value) >= 10 else f"{value:,.3f}"
    return str(value)


def write_markdown(
    path: pathlib.Path,
    summary: list[dict[str, Any]],
    failures: list[dict[str, Any]],
    problems: list[str],
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines: list[str] = ["# Benchmark summary", ""]

    if problems:
        lines += ["## Files that could not be read", ""]
        lines += [f"- {problem}" for problem in problems] + [""]

    if failures:
        lines += [
            "## Records that did not produce measurements",
            "",
            "Excluded from every statistic below.",
            "",
            "| device | engine | workload | status | reason |",
            "|---|---|---|---|---|",
        ]
        for failure in failures:
            lines.append(
                f"| {failure['device']} | {failure['engine']} | {failure['workload']} | "
                f"{failure['status']} | {failure.get('failure_reason') or ''} |"
            )
        lines.append("")

    for device in sorted({entry["device"] for entry in summary}):
        lines += [f"## {device}", ""]
        for column, label, higher in METRICS:
            entries = [
                entry for entry in summary
                if entry["device"] == device and entry.get(f"{column}_n")
            ]
            if not entries:
                lines += [
                    f"### {label}",
                    "",
                    "_Not reported by any engine on this device._",
                    "",
                ]
                continue
            direction = "higher is better" if higher else "lower is better"
            lines += [
                f"### {label}",
                f"_{direction}_",
                "",
                "| engine | model | quant | workload | n | median | mean | p90 | p95 | stddev | chunks |",
                "|---|---|---|---|---|---|---|---|---|---|---|",
            ]
            for entry in entries:
                lines.append(
                    f"| {entry['engine']} | {entry['model']} | {entry['quantization']} | "
                    f"{entry['workload']} | {entry[f'{column}_n']} | "
                    f"{format_number(entry[f'{column}_median'])} | "
                    f"{format_number(entry[f'{column}_mean'])} | "
                    f"{format_number(entry[f'{column}_p90'])} | "
                    f"{format_number(entry[f'{column}_p95'])} | "
                    f"{format_number(entry[f'{column}_stddev'])} | "
                    f"{entry['chunk_counts'] or 'n/a'} |"
                )
            lines.append("")

    lines += fairness_notes(summary)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def fairness_notes(summary: list[dict[str, Any]]) -> list[str]:
    """Say plainly when a comparison is not apples to apples."""
    lines = ["## Comparability", ""]
    quantizations = defaultdict(set)
    models = defaultdict(set)
    chunk_counts = defaultdict(set)
    for entry in summary:
        quantizations[entry["engine"]].add(entry["quantization"])
        models[entry["engine"]].add(entry["model"])
        if entry["chunk_counts"]:
            chunk_counts[entry["engine"]].add(entry["chunk_counts"])

    if len({frozenset(v) for v in quantizations.values()}) > 1:
        detail = ", ".join(f"{engine}: {sorted(q)}" for engine, q in sorted(quantizations.items()))
        lines.append(
            f"- **Quantization differs between engines** ({detail}). Throughput and memory are "
            "not comparable on equal terms; the engines are running different weights."
        )
    if len({frozenset(v) for v in models.values()}) > 1:
        detail = ", ".join(f"{engine}: {sorted(m)}" for engine, m in sorted(models.items()))
        lines.append(f"- **Model differs between engines** ({detail}).")
    if len({frozenset(v) for v in chunk_counts.values()}) > 1:
        detail = ", ".join(f"{engine}: {sorted(c)}" for engine, c in sorted(chunk_counts.items()))
        lines.append(
            f"- **Chunk counts differ between engines** ({detail}). Latency and time to first "
            "chunk are still directly comparable — both are measured by the same harness code — "
            "but chunks/sec is only comparable when a chunk means the same thing on both sides."
        )
    if len(lines) == 2:
        lines.append(
            "- Model and quantization match across engines, and every metric was measured by "
            "the same harness code above both."
        )
    return lines + [""]


def write_charts(directory: pathlib.Path, summary: list[dict[str, Any]]) -> list[str]:
    try:
        import matplotlib

        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return ["matplotlib is not installed; skipped charts (pip install matplotlib)"]

    directory.mkdir(parents=True, exist_ok=True)
    notes = []

    for column, label, _higher in METRICS:
        entries = [entry for entry in summary if entry.get(f"{column}_n")]
        if not entries:
            notes.append(f"no data for {label}; no chart written")
            continue

        devices = sorted({entry["device"] for entry in entries})
        engines = sorted({entry["engine"] for entry in entries})
        workloads = sorted({entry["workload"] for entry in entries})

        fig, axes = plt.subplots(
            len(devices), 1, figsize=(max(6, 1.6 * len(workloads) * len(engines)), 3.4 * len(devices)),
            squeeze=False,
        )
        for row, device in enumerate(devices):
            axis = axes[row][0]
            width = 0.8 / max(1, len(engines))
            for index, engine in enumerate(engines):
                xs, ys, errs = [], [], []
                for position, workload in enumerate(workloads):
                    match = next(
                        (
                            entry for entry in entries
                            if entry["device"] == device
                            and entry["engine"] == engine
                            and entry["workload"] == workload
                        ),
                        None,
                    )
                    # No bar at all where there is no measurement, rather than a zero.
                    if match is None:
                        continue
                    xs.append(position + index * width)
                    ys.append(match[f"{column}_median"])
                    errs.append(match[f"{column}_stddev"] or 0.0)
                if xs:
                    axis.bar(xs, ys, width=width, yerr=errs, capsize=3, label=engine)
            axis.set_title(f"{label} — {device}")
            axis.set_xticks([position + 0.4 - width / 2 for position in range(len(workloads))])
            axis.set_xticklabels(workloads, rotation=20, ha="right")
            axis.set_ylabel(label)
            axis.legend()

        fig.tight_layout()
        target = directory / f"{column}.png"
        fig.savefig(target, dpi=140)
        plt.close(fig)

    return notes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("results", type=pathlib.Path, help="directory holding benchmark JSON")
    parser.add_argument("--out", type=pathlib.Path, default=None, help="output directory (default: <results>)")
    parser.add_argument(
        "--strict",
        action="store_true",
        help="exit non-zero if any file failed to parse or any record failed",
    )
    args = parser.parse_args()

    if not args.results.exists():
        print(f"No such directory: {args.results}", file=sys.stderr)
        return 2

    out = args.out or args.results
    files, problems = load_files(args.results)
    rows, failures = flatten(files)
    summary = summarize(rows)

    write_csv(out / "csv" / "samples.csv", rows)
    write_csv(out / "csv" / "summary.csv", summary)
    write_markdown(out / "markdown" / "summary.md", summary, failures, problems)
    chart_notes = write_charts(out / "charts", summary)

    print(f"files:    {len(files)} parsed, {len(problems)} unreadable")
    print(f"samples:  {len(rows)} measured iterations in {len(summary)} groups")
    print(f"failures: {len(failures)} records excluded from statistics")
    for problem in problems:
        print(f"  ! {problem}", file=sys.stderr)
    for failure in failures:
        print(f"  ! {failure['engine']}/{failure['workload']}: {failure.get('failure_reason')}")
    for note in chart_notes:
        print(f"  - {note}")
    print(f"wrote:    {out / 'csv'}, {out / 'markdown'}, {out / 'charts'}")

    if args.strict and (problems or failures):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
