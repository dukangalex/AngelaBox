#!/usr/bin/env bash
# Pre-flight gate (beefed-up): fail fast before a release build starts.
# Usage: bash scripts/release_preflight_gate.sh <version_tag> [version.properties]
# Requires: git, gh (GH_TOKEN set), jq. Must run from the repo root checkout.
set -euo pipefail
TAG="${1:?usage: release_preflight_gate.sh <version_tag> [properties-file]}"
PROPERTIES="${2:-version.properties}"

# Gate 1: release must be cut from the tip of dev, with a clean version.properties.
DEV_TIP=$(git ls-remote origin refs/heads/dev | awk '{print $1}')
HEAD_SHA=$(git rev-parse HEAD)
test -n "$DEV_TIP"
if [ "$HEAD_SHA" != "$DEV_TIP" ]; then
  echo "Release checkout $HEAD_SHA is not the tip of origin/dev ($DEV_TIP) — refusing to release a stale commit" >&2
  exit 1
fi
if [ ! -f "$PROPERTIES" ]; then
  echo "Release properties file is missing: $PROPERTIES" >&2
  exit 1
fi
if [ -n "$(git status --porcelain -- "$PROPERTIES")" ]; then
  echo "$PROPERTIES has uncommitted changes — refusing to release" >&2
  exit 1
fi
echo "Pre-flight OK: releasing tip of dev ($HEAD_SHA), $PROPERTIES clean"

# Gate 2: the ChainBox CI run for this exact commit must be green.
CI_JSON=$(gh run list --workflow "ChainBox CI" --limit 20 \
  --json headSha,status,conclusion,createdAt \
  --jq "[.[] | select(.headSha == \"$HEAD_SHA\")] | sort_by(.createdAt) | last // empty")
if [ -z "$CI_JSON" ]; then
  echo "No ChainBox CI run found for $HEAD_SHA — refusing to release an untested commit" >&2
  exit 1
fi
CI_STATUS=$(printf '%s' "$CI_JSON" | jq -r '.status')
CI_CONCLUSION=$(printf '%s' "$CI_JSON" | jq -r '.conclusion')
if [ "$CI_STATUS" != "completed" ] || [ "$CI_CONCLUSION" != "success" ]; then
  echo "ChainBox CI for $HEAD_SHA is $CI_STATUS/$CI_CONCLUSION — refusing to release" >&2
  exit 1
fi
echo "Pre-flight OK: ChainBox CI green for $HEAD_SHA"
