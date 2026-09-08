#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESOLVER="${ROOT}/.github/scripts/resolve_release_publication_state.sh"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

REQUIRED_ASSETS=(
  EnthusiaLoreItems.jar
  EnthusiaLoreItems.jar.sha256
  bom.cyclonedx.json
  gradle-dependencies.txt
  normalized-entry-manifest.txt
  wp04-profile.json
  EnthusiaLoreItems-test-reports.tar.gz
  acceptance-index.md
  rollback-instructions.md
)

fail() {
  echo "release publication-state regression failed: $*" >&2
  exit 1
}

assert_output() {
  local expected="$1"
  grep -Fx "${expected}" "${LAST_DIR}/output" >/dev/null || \
    fail "${LAST_SCENARIO}: missing output ${expected}"
}

assert_empty_output() {
  test ! -s "${LAST_DIR}/output" || \
    fail "${LAST_SCENARIO}: expected no outputs, got $(tr '\n' ';' < "${LAST_DIR}/output")"
}

run_case() {
  local scenario="$1"
  local dir="${TMP_ROOT}/${scenario}"
  mkdir -p "${dir}"
  : > "${dir}/output"

  set +e
  (
    export GITHUB_REPOSITORY="wsg138/EnthusiaLoreItems"
    export GITHUB_OUTPUT="${dir}/output"
    export EVENT_TARGET_SHA="target-sha"
    export EVENT_CI_RUN_ID="12345"
    export FINAL_TAG="v1.0.0"

    gh() {
      if [[ "$1" == "api" && "$2" == */releases/tags/* ]]; then
        case "${scenario}" in
          release-forbidden)
            printf 'gh: Forbidden (HTTP 403)\n' >&2
            return 43
            ;;
          release-ratelimit)
            printf 'gh: rate limited (HTTP 429)\n' >&2
            return 44
            ;;
          release-server)
            printf 'gh: upstream failure (HTTP 500)\n' >&2
            return 50
            ;;
          release|release-draft|release-prerelease|release-extra-asset)
            case " $* " in
              *" [.tag_name, .draft, .prerelease] | @tsv "*)
                case "${scenario}" in
                  release|release-extra-asset) printf '%s\tfalse\tfalse\n' "${FINAL_TAG}" ;;
                  release-draft) printf '%s\ttrue\tfalse\n' "${FINAL_TAG}" ;;
                  release-prerelease) printf '%s\tfalse\ttrue\n' "${FINAL_TAG}" ;;
                esac
                ;;
              *" .assets[].name "*)
                if [[ "${scenario}" == "release-draft" ]]; then
                  printf 'draft asset inventory must not be trusted before rebuild\n' >&2
                  return 95
                fi
                printf '%s\n' "${REQUIRED_ASSETS[@]}"
                if [[ "${scenario}" == "release-extra-asset" ]]; then
                  printf 'unexpected.bin\n'
                fi
                ;;
              *)
                printf 'unexpected release API invocation: %s\n' "$*" >&2
                return 96
                ;;
            esac
            return 0
            ;;
          *)
            printf 'gh: Not Found (HTTP 404)\n' >&2
            return 1
            ;;
        esac
      fi

      if [[ "$1" == "api" && "$2" == */git/ref/tags/* ]]; then
        case "${scenario}" in
          missing)
            printf 'null\n'
            printf 'gh: Not Found (HTTP 404)\n' >&2
            return 1
            ;;
          null)
            printf 'null\n'
            return 0
            ;;
          exact|release|release-draft|release-prerelease|release-extra-asset)
            printf '%s\n' "${EVENT_TARGET_SHA}"
            return 0
            ;;
          forbidden)
            printf 'gh: Forbidden (HTTP 403)\n' >&2
            return 43
            ;;
          ratelimit)
            printf 'gh: rate limited (HTTP 429)\n' >&2
            return 44
            ;;
          server)
            printf 'gh: upstream failure (HTTP 500)\n' >&2
            return 50
            ;;
          *)
            return 91
            ;;
        esac
      fi

      if [[ "$1" == "api" && "$2" == */git/ref/heads/main ]]; then
        if [[ "${scenario}" == "missing" ]]; then
          printf '%s\n' "${EVENT_TARGET_SHA}"
          return 0
        fi
        printf 'main ref unexpectedly queried for scenario %s\n' "${scenario}" >&2
        return 92
      fi

      printf 'unexpected gh invocation: %s\n' "$*" >&2
      return 93
    }

    # shellcheck source=.github/scripts/resolve_release_publication_state.sh
    source "${RESOLVER}"
  ) >"${dir}/stdout" 2>"${dir}/stderr"
  LAST_RC=$?
  set -e
  LAST_DIR="${dir}"
  LAST_SCENARIO="${scenario}"
}

run_case missing
test "${LAST_RC}" -eq 0 || fail "missing: expected success, got ${LAST_RC}"
assert_output "target_sha=target-sha"
assert_output "ci_run_id=12345"
assert_output "tag_exists=false"
assert_output "release_exists=false"
assert_output "release_draft=false"
assert_output "released=false"
test "$(wc -l < "${LAST_DIR}/output")" -eq 6 || fail "missing: unexpected extra outputs"

run_case null
test "${LAST_RC}" -ne 0 || fail "null: successful null tag lookup must fail closed"
assert_empty_output

run_case exact
test "${LAST_RC}" -eq 0 || fail "exact: expected success, got ${LAST_RC}"
assert_output "target_sha=target-sha"
assert_output "ci_run_id=12345"
assert_output "tag_exists=true"
assert_output "release_exists=false"
assert_output "release_draft=false"
assert_output "released=false"
test "$(wc -l < "${LAST_DIR}/output")" -eq 6 || fail "exact: unexpected extra outputs"

for case_name in forbidden ratelimit server; do
  run_case "${case_name}"
  case "${case_name}" in
    forbidden) expected_rc=43; expected_http=403 ;;
    ratelimit) expected_rc=44; expected_http=429 ;;
    server) expected_rc=50; expected_http=500 ;;
  esac
  test "${LAST_RC}" -eq "${expected_rc}" || \
    fail "${case_name}: expected exit ${expected_rc}, got ${LAST_RC}"
  grep -F "HTTP ${expected_http}" "${LAST_DIR}/stderr" >/dev/null || \
    fail "${case_name}: missing retained HTTP ${expected_http} diagnostic"
  assert_empty_output
done

for case_name in release-forbidden release-ratelimit release-server; do
  run_case "${case_name}"
  case "${case_name}" in
    release-forbidden) expected_rc=43; expected_http=403 ;;
    release-ratelimit) expected_rc=44; expected_http=429 ;;
    release-server) expected_rc=50; expected_http=500 ;;
  esac
  test "${LAST_RC}" -eq "${expected_rc}" || \
    fail "${case_name}: expected exit ${expected_rc}, got ${LAST_RC}"
  grep -F "HTTP ${expected_http}" "${LAST_DIR}/stderr" >/dev/null || \
    fail "${case_name}: missing retained HTTP ${expected_http} diagnostic"
  assert_empty_output
done

run_case release
test "${LAST_RC}" -eq 0 || fail "release: expected success, got ${LAST_RC}"
assert_output "target_sha=target-sha"
assert_output "ci_run_id=12345"
assert_output "tag_exists=true"
assert_output "release_exists=true"
assert_output "release_draft=false"
assert_output "released=false"
test "$(wc -l < "${LAST_DIR}/output")" -eq 6 || \
  fail "release: existing production release must route through exact evidence revalidation"

run_case release-draft
test "${LAST_RC}" -eq 0 || fail "release-draft: expected recoverable success, got ${LAST_RC}"
assert_output "target_sha=target-sha"
assert_output "ci_run_id=12345"
assert_output "tag_exists=true"
assert_output "release_exists=true"
assert_output "release_draft=true"
assert_output "released=false"
test "$(wc -l < "${LAST_DIR}/output")" -eq 6 || \
  fail "release-draft: interrupted draft must route through rebuild"

run_case release-prerelease
test "${LAST_RC}" -ne 0 || fail "release-prerelease: prerelease state must fail closed"
assert_empty_output

run_case release-extra-asset
test "${LAST_RC}" -ne 0 || fail "release-extra-asset: unexpected published asset must fail closed"
assert_empty_output

echo "RELEASE_PUBLICATION_STATE_OK"
