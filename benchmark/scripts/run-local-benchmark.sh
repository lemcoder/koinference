#!/usr/bin/env bash
#
# Run the harness on this machine (macOS arm64) against both engines and analyse the output.
#
#   ./benchmark/scripts/run-local-benchmark.sh /path/to/stories260K.gguf /path/to/model.litertlm
#
# Verifies the harness, not the hardware: memory, thermal and battery are Android-only and are
# reported as null here. For numbers that mean something, use run-ftl-benchmark.sh.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GGUF="${1:-}"
LITERTLM="${2:-}"
RESULTS="${3:-${REPO_ROOT}/results}"

[[ -f "${GGUF}" ]] || { echo "usage: $0 <model.gguf> <model.litertlm> [results-dir]" >&2; exit 2; }
[[ -f "${LITERTLM}" ]] || { echo "usage: $0 <model.gguf> <model.litertlm> [results-dir]" >&2; exit 2; }

mkdir -p "${RESULTS}/raw"

cd "${REPO_ROOT}"
KOI_TEST_GGUF="${GGUF}" \
KOI_TEST_LITERTLM="${LITERTLM}" \
KOI_BENCH_FIXTURES="${REPO_ROOT}/benchmark/fixtures/prompts.json" \
KOI_BENCH_OUT="${RESULTS}/raw" \
    ./gradlew :benchmark:core:macosArm64Test --rerun-tasks

python3 benchmark/analysis/analyze_results.py "${RESULTS}"
