#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"
: "${EVENT_TARGET_SHA:?EVENT_TARGET_SHA is required}"
: "${EVENT_CI_RUN_ID:?EVENT_CI_RUN_ID is required}"
: "${FINAL_TAG:?FINAL_TAG is required}"

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

emit_state() {
  local tag_exists="$1"
  local release_exists="$2"
  local release_draft="$3"
  local release_id="${4:-}"
  local release_tag_sha="${5:-}"
  echo "target_sha=${EVENT_TARGET_SHA}" >> "${GITHUB_OUTPUT}"
  echo "ci_run_id=${EVENT_CI_RUN_ID}" >> "${GITHUB_OUTPUT}"
  echo "tag_exists=${tag_exists}" >> "${GITHUB_OUTPUT}"
  echo "release_exists=${release_exists}" >> "${GITHUB_OUTPUT}"
  echo "release_draft=${release_draft}" >> "${GITHUB_OUTPUT}"
  echo "release_id=${release_id}" >> "${GITHUB_OUTPUT}"
  echo "release_tag_sha=${release_tag_sha}" >> "${GITHUB_OUTPUT}"
  echo "released=false" >> "${GITHUB_OUTPUT}"
}

RELEASE_LOOKUP_ERROR="$(mktemp)"
if RELEASE_METADATA="$(gh api "repos/${GITHUB_REPOSITORY}/releases/tags/${FINAL_TAG}" \
  --jq '[.id, .tag_name, .draft, .prerelease] | @tsv' 2>"${RELEASE_LOOKUP_ERROR}")"; then
  rm -f "${RELEASE_LOOKUP_ERROR}"
  TAG_SHA="$(gh api "repos/${GITHUB_REPOSITORY}/git/ref/tags/${FINAL_TAG}" --jq '.object.sha')"
  IFS=$'\t' read -r RELEASE_ID RELEASE_TAG RELEASE_DRAFT RELEASE_PRERELEASE <<<"${RELEASE_METADATA}"
  test -n "${RELEASE_ID}"
  test "${RELEASE_ID}" != "null"
  test "${RELEASE_TAG}" = "${FINAL_TAG}"
  test "${RELEASE_DRAFT}" = "false"
  test "${RELEASE_PRERELEASE}" = "false"
  ASSETS="$(gh api "repos/${GITHUB_REPOSITORY}/releases/tags/${FINAL_TAG}" --jq '.assets[].name')"
  ASSET_COUNT="$(printf '%s\n' "${ASSETS}" | sed '/^$/d' | wc -l)"
  test "${ASSET_COUNT}" -eq "${#REQUIRED_ASSETS[@]}"
  for asset in "${REQUIRED_ASSETS[@]}"; do
    grep -Fx "${asset}" <<<"${ASSETS}" >/dev/null
  done
  emit_state true true false "${RELEASE_ID}" "${TAG_SHA}"
  exit 0
else
  RELEASE_LOOKUP_STATUS=$?
  if ! grep -Eq '(^|[^0-9])HTTP 404([^0-9]|$)' "${RELEASE_LOOKUP_ERROR}"; then
    cat "${RELEASE_LOOKUP_ERROR}" >&2
    rm -f "${RELEASE_LOOKUP_ERROR}"
    exit "${RELEASE_LOOKUP_STATUS}"
  fi
  rm -f "${RELEASE_LOOKUP_ERROR}"
fi

# GitHub's releases/tags endpoint intentionally does not expose draft releases.
# Fall back to the authenticated releases collection only after an explicit 404 so
# interrupted draft publication is recoverable without treating other API errors as missing.
RELEASES_JSON="$(gh api "repos/${GITHUB_REPOSITORY}/releases?per_page=100")"
DRAFT_MATCH_COUNT="$(jq --arg tag "${FINAL_TAG}" '[.[] | select(.tag_name == $tag)] | length' <<<"${RELEASES_JSON}")"
test "${DRAFT_MATCH_COUNT}" -le 1
if [[ "${DRAFT_MATCH_COUNT}" -eq 1 ]]; then
  RELEASE_ID="$(jq -r --arg tag "${FINAL_TAG}" '.[] | select(.tag_name == $tag) | .id' <<<"${RELEASES_JSON}")"
  RELEASE_DRAFT="$(jq -r --arg tag "${FINAL_TAG}" '.[] | select(.tag_name == $tag) | .draft' <<<"${RELEASES_JSON}")"
  RELEASE_PRERELEASE="$(jq -r --arg tag "${FINAL_TAG}" '.[] | select(.tag_name == $tag) | .prerelease' <<<"${RELEASES_JSON}")"
  RELEASE_TARGET="$(jq -r --arg tag "${FINAL_TAG}" '.[] | select(.tag_name == $tag) | .target_commitish' <<<"${RELEASES_JSON}")"
  test -n "${RELEASE_ID}"
  test "${RELEASE_ID}" != "null"
  test "${RELEASE_DRAFT}" = "true"
  test "${RELEASE_PRERELEASE}" = "false"
  TAG_SHA="$(gh api "repos/${GITHUB_REPOSITORY}/git/ref/tags/${FINAL_TAG}" --jq '.object.sha')"
  test "${TAG_SHA}" = "${EVENT_TARGET_SHA}"
  test "${RELEASE_TARGET}" = "${EVENT_TARGET_SHA}"
  emit_state true true true "${RELEASE_ID}" "${TAG_SHA}"
  exit 0
fi

TAG_LOOKUP_ERROR="$(mktemp)"
if TAG_SHA="$(gh api "repos/${GITHUB_REPOSITORY}/git/ref/tags/${FINAL_TAG}" --jq '.object.sha' 2>"${TAG_LOOKUP_ERROR}")"; then
  rm -f "${TAG_LOOKUP_ERROR}"
  test -n "${TAG_SHA}"
  test "${TAG_SHA}" != "null"
  test "${TAG_SHA}" = "${EVENT_TARGET_SHA}"
  emit_state true false false "" "${TAG_SHA}"
  exit 0
else
  TAG_LOOKUP_STATUS=$?
  if ! grep -Eq '(^|[^0-9])HTTP 404([^0-9]|$)' "${TAG_LOOKUP_ERROR}"; then
    cat "${TAG_LOOKUP_ERROR}" >&2
    rm -f "${TAG_LOOKUP_ERROR}"
    exit "${TAG_LOOKUP_STATUS}"
  fi
  rm -f "${TAG_LOOKUP_ERROR}"
fi

MAIN_SHA="$(gh api "repos/${GITHUB_REPOSITORY}/git/ref/heads/main" --jq '.object.sha')"
test "${EVENT_TARGET_SHA}" = "${MAIN_SHA}"
emit_state false false false
