#!/usr/bin/env bash
# Print only the current version's section of docs/RELEASE_NOTES.md.
# Fails fast if the section is missing, so a release never ships an empty changelog.
# Must run from the repo root checkout.
set -euo pipefail
VERSION_NAME=$(grep '^VERSION_NAME=' version.properties | cut -d= -f2- | tr -d '\r')
VER_SHORT="${VERSION_NAME%-*}"
NOTES_SECTION=$(awk -v ver="$VER_SHORT" '
  index($0, "• **") == 1 { in_sec = (index($0, "**" ver "：**") > 0) }
  in_sec
' docs/RELEASE_NOTES.md)
if [ -z "$NOTES_SECTION" ]; then
  echo "docs/RELEASE_NOTES.md has no section for version $VERSION_NAME — refusing to publish an empty changelog" >&2
  exit 1
fi
printf '%s\n' "$NOTES_SECTION"
