#!/usr/bin/env bash
#
# Check a device matrix against the live Firebase Test Lab catalogue.
#
#   ./benchmark/scripts/validate-device-matrix.sh benchmark/scripts/devices.yaml
#   ./benchmark/scripts/validate-device-matrix.sh --catalog          # what is currently offered
#   ./benchmark/scripts/validate-device-matrix.sh --suggest          # a starting matrix, generated
#
# FTL model ids are Google's internal codenames (shiba, bluejay, b0q) and the catalogue changes
# as devices are retired, so a matrix that worked last quarter can silently stop matching. This
# fails loudly instead: an unknown id, or a version that device does not offer, is an error.
#
# Requires FIREBASE_PROJECT_ID and an authenticated gcloud.
set -euo pipefail

fail() { echo "error: $*" >&2; exit 1; }

command -v gcloud >/dev/null || fail "gcloud is not installed"
[[ -n "${FIREBASE_PROJECT_ID:-}" ]] || fail "FIREBASE_PROJECT_ID is not set"

catalog() {
    gcloud firebase test android models list \
        --project "${FIREBASE_PROJECT_ID}" \
        --format="csv[no-heading](MODEL_ID,MAKE,MODEL_NAME,FORM,OS_VERSION_IDS)"
}

case "${1:-}" in
    --catalog)
        printf '%-14s %-10s %-26s %-8s %s\n' MODEL_ID MAKE NAME FORM VERSIONS
        catalog | while IFS=, read -r id make name form versions; do
            printf '%-14s %-10s %-26s %-8s %s\n' "$id" "$make" "$name" "$form" "$versions"
        done
        exit 0
        ;;
    --suggest)
        # A starting point, not a recommendation: physical devices only, newest OS each, one per
        # make. The choice of which SoCs matter is yours — this only guarantees the ids exist.
        echo "# Generated $(date -u +%Y-%m-%d) from the live catalogue. Review before using."
        echo "devices:"
        catalog | awk -F, '$4 == "PHYSICAL"' | sort -t, -k2,2 -u | head -6 | \
            while IFS=, read -r id make name form versions; do
                newest="${versions##*;}"
                newest="${newest// /}"
                echo "  - model: ${id}"
                echo "    version: ${newest}"
                echo "    note: ${make} ${name}"
            done
        exit 0
        ;;
    "")
        fail "usage: $0 <devices.yaml> | --catalog | --suggest"
        ;;
esac

MATRIX="$1"
[[ -f "${MATRIX}" ]] || fail "No such matrix file: ${MATRIX}"

CATALOG="$(catalog)"
[[ -n "${CATALOG}" ]] || fail "The catalogue came back empty — check the project and credentials"

problems=0
current_model=""
while IFS= read -r line; do
    [[ "${line}" =~ ^[[:space:]]*#.*$ || -z "${line// }" ]] && continue

    if [[ "${line}" =~ model:[[:space:]]*\"?([A-Za-z0-9._-]+)\"? ]]; then
        current_model="${BASH_REMATCH[1]}"
        continue
    fi

    if [[ "${line}" =~ version:[[:space:]]*\"?([0-9]+)\"? ]]; then
        version="${BASH_REMATCH[1]}"
        entry="$(echo "${CATALOG}" | awk -F, -v m="${current_model}" '$1 == m')"

        if [[ -z "${entry}" ]]; then
            echo "MISSING  ${current_model}: not in the catalogue" >&2
            problems=$((problems + 1))
            continue
        fi

        versions="$(echo "${entry}" | cut -d, -f5)"
        if [[ ";${versions};" != *";${version};"* && "${versions}" != *"${version}"* ]]; then
            echo "VERSION  ${current_model}: does not offer API ${version} (has: ${versions})" >&2
            problems=$((problems + 1))
            continue
        fi

        make="$(echo "${entry}" | cut -d, -f2)"
        name="$(echo "${entry}" | cut -d, -f3)"
        echo "ok       ${current_model}@${version}  ${make} ${name}"
    fi
done < "${MATRIX}"

echo
if [[ "${problems}" -gt 0 ]]; then
    fail "${problems} matrix entries are not runnable. Fix them or regenerate with --suggest."
fi
echo "every entry in ${MATRIX} exists in the catalogue"
