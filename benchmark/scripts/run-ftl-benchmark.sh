#!/usr/bin/env bash
#
# Build the benchmark APKs, run them on Firebase Test Lab, and bring the results back.
#
#   ./benchmark/scripts/run-ftl-benchmark.sh \
#       --matrix benchmark/scripts/devices.yaml \
#       --engine all \
#       --model gs://my-bucket/models/SmolLM2_135M_Instruct.litertlm \
#       --iterations 5
#
# One gcloud invocation per (device, engine): a matrix shard that fails can be retried on its
# own, and — more importantly — each engine gets a process that no other engine has already
# heated or grown a heap in. `--engine all` here means "each engine, separately", not "all of
# them in one process"; the latter is what passing `-e engine all` to the instrumentation does,
# and the runner marks those records as contaminated.
#
# Authentication comes from the environment, never from this file:
#   FIREBASE_PROJECT_ID            required
#   GOOGLE_APPLICATION_CREDENTIALS optional service-account key; otherwise `gcloud auth login`
#   FTL_RESULTS_BUCKET             optional gs:// bucket for raw FTL output
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MATRIX="${REPO_ROOT}/benchmark/scripts/devices.yaml"
ENGINES="all"
MODEL_URI=""
MODEL_ID=""
MODEL_VERSION="unknown"
QUANTIZATION="unknown"
MODEL_SHA=""
PROMPT_SET="default"
ITERATIONS=5
WARMUP=1
MAX_NEW_TOKENS=128
SUSTAINED_SECONDS=0
TIMEOUT="45m"
RESULTS_DIR="${REPO_ROOT}/results"
DRY_RUN=0

usage() {
    sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --matrix) MATRIX="$2"; shift 2 ;;
        --engine) ENGINES="$2"; shift 2 ;;
        --model) MODEL_URI="$2"; shift 2 ;;
        --model-id) MODEL_ID="$2"; shift 2 ;;
        --model-version) MODEL_VERSION="$2"; shift 2 ;;
        --quantization) QUANTIZATION="$2"; shift 2 ;;
        --model-sha256) MODEL_SHA="$2"; shift 2 ;;
        --prompt-set) PROMPT_SET="$2"; shift 2 ;;
        --iterations) ITERATIONS="$2"; shift 2 ;;
        --warmup) WARMUP="$2"; shift 2 ;;
        --max-new-tokens) MAX_NEW_TOKENS="$2"; shift 2 ;;
        --sustained-seconds) SUSTAINED_SECONDS="$2"; shift 2 ;;
        --timeout) TIMEOUT="$2"; shift 2 ;;
        --results) RESULTS_DIR="$2"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) usage 0 ;;
        *) echo "Unknown argument: $1" >&2; usage 2 ;;
    esac
done

fail() { echo "error: $*" >&2; exit 1; }

command -v gcloud >/dev/null || fail "gcloud is not installed (https://cloud.google.com/sdk/docs/install)"
[[ -n "${FIREBASE_PROJECT_ID:-}" ]] || fail "FIREBASE_PROJECT_ID is not set. Project ids are never hard-coded here."
[[ -n "${MODEL_URI}" ]] || fail "--model is required: a gs:// URI or a local path to the weights"
[[ -f "${MATRIX}" ]] || fail "No device matrix at ${MATRIX}"

if [[ -n "${GOOGLE_APPLICATION_CREDENTIALS:-}" ]]; then
    gcloud auth activate-service-account --key-file "${GOOGLE_APPLICATION_CREDENTIALS}" >/dev/null
fi

# The engines the harness knows. Kept in step with availableEngines() in the Kotlin source; an
# unknown id fails the record there, so a typo here is caught rather than silently skipped.
if [[ "${ENGINES}" == "all" ]]; then
    ENGINE_LIST=(llama.cpp litert-lm)
else
    IFS=',' read -r -a ENGINE_LIST <<< "${ENGINES}"
fi

[[ -n "${MODEL_ID}" ]] || MODEL_ID="$(basename "${MODEL_URI}" | sed 's/\.[^.]*$//')"

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$(printf '%04x' $((RANDOM)))"
RAW_DIR="${RESULTS_DIR}/raw"
LOG_DIR="${RESULTS_DIR}/logs"
mkdir -p "${RAW_DIR}" "${LOG_DIR}"

echo "run id:   ${RUN_ID}"
echo "project:  ${FIREBASE_PROJECT_ID}"
echo "matrix:   ${MATRIX}"
echo "engines:  ${ENGINE_LIST[*]}"
echo "model:    ${MODEL_URI} (${QUANTIZATION})"
echo

# ── build ────────────────────────────────────────────────────────────────────────────────────
echo "building APKs"
if [[ "${DRY_RUN}" -eq 0 ]]; then
    (cd "${REPO_ROOT}" && ./gradlew :benchmark:core:assembleAndroidDeviceTest)
    (cd "${REPO_ROOT}/benchmark/stub-app" && ./gradlew assembleBenchmark)
fi

APP_APK="${REPO_ROOT}/benchmark/stub-app/build/outputs/apk/benchmark/koinference-benchmark-stub-app-benchmark.apk"
TEST_APK="${REPO_ROOT}/benchmark/core/build/outputs/apk/androidTest/core-androidTest.apk"

if [[ "${DRY_RUN}" -eq 0 ]]; then
    [[ -f "${APP_APK}" ]] || fail "app APK not found at ${APP_APK}"
    [[ -f "${TEST_APK}" ]] || fail "test APK not found at ${TEST_APK}"
fi

# ── device matrix ────────────────────────────────────────────────────────────────────────────
# Parsed with the YAML subset the matrix file actually uses (a list of model/version pairs), so
# the script needs no Python or yq. validate-device-matrix.sh checks the ids against the live
# catalogue; nothing here invents one.
DEVICES=()
while IFS= read -r line; do
    [[ "${line}" =~ ^[[:space:]]*#.*$ || -z "${line// }" ]] && continue
    if [[ "${line}" =~ model:[[:space:]]*\"?([A-Za-z0-9._-]+)\"? ]]; then
        current_model="${BASH_REMATCH[1]}"
    fi
    if [[ "${line}" =~ version:[[:space:]]*\"?([0-9]+)\"? ]]; then
        DEVICES+=("${current_model}:${BASH_REMATCH[1]}")
    fi
done < "${MATRIX}"

[[ ${#DEVICES[@]} -gt 0 ]] || fail "No devices parsed from ${MATRIX}"
echo "devices:  ${DEVICES[*]}"
echo

# ── run ──────────────────────────────────────────────────────────────────────────────────────
FAILED=0
for device in "${DEVICES[@]}"; do
    model_id="${device%%:*}"
    version="${device##*:}"

    for engine in "${ENGINE_LIST[@]}"; do
        shard="${model_id}-${version}-${engine//./_}"
        echo "── ${shard}"

        # Declared empty and expanded with :- because `set -u` treats an empty array as unset
        # on bash 3.2, which is what macOS ships.
        results_bucket_args=()
        if [[ -n "${FTL_RESULTS_BUCKET:-}" ]]; then
            results_bucket_args=(--results-bucket "${FTL_RESULTS_BUCKET}")
        fi

        # --other-files puts the model and the prompt corpus on the device before the test runs;
        # --directories-to-pull brings the artifacts back. Both are FTL features rather than
        # anything the harness does itself, so a run that dies mid-way still returns its log.
        args=(
            firebase test android run
            --type instrumentation
            --project "${FIREBASE_PROJECT_ID}"
            --app "${APP_APK}"
            --test "${TEST_APK}"
            --device "model=${model_id},version=${version},locale=en,orientation=portrait"
            --timeout "${TIMEOUT}"
            --results-dir "${RUN_ID}/${shard}"
            --directories-to-pull "/sdcard/Download/koinference"
            --other-files "/sdcard/Download/koinference/model.bin=${MODEL_URI}"
            --other-files "/sdcard/Download/koinference/prompts.json=${REPO_ROOT}/benchmark/fixtures/prompts.json"
            --test-targets "class io.github.lemcoder.koinference.benchmark.BenchmarkInstrumentation"
            ${results_bucket_args[@]+"${results_bucket_args[@]}"}
        )

        # Instrumentation arguments the harness reads. runId and the FTL identity are passed in
        # because nothing on the device can report which matrix entry it was launched as.
        # One --environment-variables flag, not two: a second occurrence replaces the first
        # rather than adding to it, which silently dropped clearPackageData.
        instrumentation_args=(
            "clearPackageData=true"
            "engine=${engine}"
            "model=/sdcard/Download/koinference/model.bin"
            "modelId=${MODEL_ID}"
            "modelVersion=${MODEL_VERSION}"
            "quantization=${QUANTIZATION}"
            "promptFile=/sdcard/Download/koinference/prompts.json"
            "promptSet=${PROMPT_SET}"
            "iterations=${ITERATIONS}"
            "warmup=${WARMUP}"
            "maxNewTokens=${MAX_NEW_TOKENS}"
            "sustainedDurationSeconds=${SUSTAINED_SECONDS}"
            "outputDir=/sdcard/Download/koinference"
            "runId=${RUN_ID}"
            "ftlModelId=${model_id}"
            "ftlVersion=${version}"
        )
        [[ -n "${MODEL_SHA}" ]] && instrumentation_args+=("modelSha256=${MODEL_SHA}")

        joined="$(IFS=,; echo "${instrumentation_args[*]}")"
        args+=(--environment-variables "${joined}")

        if [[ "${DRY_RUN}" -eq 1 ]]; then
            printf 'gcloud'; printf ' %q' "${args[@]}"; printf '\n\n'
            continue
        fi

        if gcloud "${args[@]}" 2>&1 | tee "${LOG_DIR}/${shard}.log"; then
            echo "  passed"
        else
            # Kept going on purpose: one device failing must not cost the results of the rest.
            echo "  FAILED — see ${LOG_DIR}/${shard}.log" >&2
            FAILED=$((FAILED + 1))
        fi

        if [[ -n "${FTL_RESULTS_BUCKET:-}" ]]; then
            gsutil -m cp -r "${FTL_RESULTS_BUCKET}/${RUN_ID}/${shard}/**/benchmark-results.json" \
                "${RAW_DIR}/${shard}.json" 2>/dev/null \
                || echo "  no benchmark-results.json for ${shard}" >&2
            gsutil -m cp -r "${FTL_RESULTS_BUCKET}/${RUN_ID}/${shard}/**/benchmark-log.txt" \
                "${LOG_DIR}/${shard}-device.txt" 2>/dev/null || true
        fi
    done
done

echo
if [[ -z "${FTL_RESULTS_BUCKET:-}" ]]; then
    cat >&2 <<'NOTE'
FTL_RESULTS_BUCKET is not set, so artifacts were left in the bucket gcloud chose. Download them
with the "Raw results" link gcloud printed, or set FTL_RESULTS_BUCKET and re-run to have this
script copy benchmark-results.json into results/raw/ automatically.
NOTE
fi

echo "raw results: ${RAW_DIR}"
echo "logs:        ${LOG_DIR}"
echo "analyse:     python3 benchmark/analysis/analyze_results.py ${RESULTS_DIR}"

exit $(( FAILED > 0 ? 1 : 0 ))
