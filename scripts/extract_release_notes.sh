#!/usr/bin/env bash
# Print only one version section of docs/RELEASE_NOTES.md.
# Argument is optional: v1.0.115-beta, 1.0.115-stable, or 1.0.115.
# With no argument, use version.properties. Never print older sections.
set -euo pipefail
RAW="${1:-}"
if [ -n "$RAW" ]; then
  VER_SHORT=$(printf '%s' "$RAW" | sed -e 's/^v//' -e 's/-beta$//' -e 's/-stable$//')
else
  VERSION_NAME=$(grep '^VERSION_NAME=' version.properties | cut -d= -f2- | tr -d '\r')
  VER_SHORT="${VERSION_NAME%-*}"
fi
NOTES_SECTION=$(awk -v ver="$VER_SHORT" '
  index($0, "• **") == 1 { in_sec = (index($0, "**" ver "：**") > 0) }
  in_sec
' docs/RELEASE_NOTES.md)
if [ -z "$NOTES_SECTION" ]; then
  echo "docs/RELEASE_NOTES.md has no section for version $VER_SHORT — refusing to publish an empty or historical changelog" >&2
  exit 1
fi
printf '%s\n' "$NOTES_SECTION"