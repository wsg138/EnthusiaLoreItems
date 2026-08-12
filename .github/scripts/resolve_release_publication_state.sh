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

if gh release view "${FINAL_TAG}" --repo "${GITHUB_REPOSITORY}" >/dev/null 2>&1; then
  TAG_SHA="$(gh api "repos/${GITHUB_REPOSITORY}/git/ref/tags/${FINAL_TAG}" --jq '.object.sha')"
  test "${TAG_SHA}" = "${EVENT_TARGET_SHA}"
  RELEASE_TAG="$(gh release view "${FINAL_TAG}" --repo "${GITHUB_REPOSITORY}" --json tagName --jq '.tagName')"
  test "${RELEASE_TAG}" = "${FINAL_TAG}"
  ASSETS="$(gh release view "${FINAL_TAG}" --repo "${GITHUB_REPOSITORY}" --json assets --jq '.assets[].name')"
  for asset in "${REQUIRED_ASSETS[@]}"; do
    grep -Fx "${asset}" <<<"${ASSETS}" >/dev/null
  done
  echo "released=true" >> "${GITHUB_OUTPUT}"
  exit 0
fi

TAG_LOOKUP_ERROR="$(mktemp)"
if TAG_SHA="$(gh api "repos/${GITHUB_REPOSITORY}/git/ref/tags/${FINAL_TAG}" --jq '.object.sha' 2>"${TAG_LOOKUP_ERROR}")"; then
  rm -f "${TAG_LOOKUP_ERROR}"
  test -n "${TAG_SHA}"
  test "${TAG_SHA}" != "null"
  test "${TAG_SHA}" = "${EVENT_TARGET_SHA}"
  echo "target_sha=${EVENT_TARGET_SHA}" >> "${GITHUB_OUTPUT}"
  echo "ci_run_id=${EVENT_CI_RUN_ID}" >> "${GITHUB_OUTPUT}"
  echo "tag_exists=true" >> "${GITHUB_OUTPUT}"
  echo "released=false" >> "${GITHUB_OUTPUT}"
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
echo "target_sha=${EVENT_TARGET_SHA}" >> "${GITHUB_OUTPUT}"
echo "ci_run_id=${EVENT_CI_RUN_ID}" >> "${GITHUB_OUTPUT}"
echo "tag_exists=false" >> "${GITHUB_OUTPUT}"
echo "released=false" >> "${GITHUB_OUTPUT}"
