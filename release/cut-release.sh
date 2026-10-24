#!/usr/bin/env bash
# Cut a release from whatever the repository currently pins.
#
#   ./release/cut-release.sh --track=beta  --dry-run
#   ./release/cut-release.sh --track=beta  --yes
#   ./release/cut-release.sh --track=stable --yes
#
# Unlike push-and-release.sh (which was written for the 1.0.128/1.0.129
# security release and hard-codes that kernel and those version numbers), this
# reads everything from version.properties / version-stable.properties and the
# release notes, so it stays correct after the next bump. It never edits files:
# bump the version and write the notes first, commit, then run this.
#
# Needs GH_TOKEN (or a gh login) with, on dukangalex/AngelaBox: Contents:rw,
# Actions:rw, Workflows:rw. --dry-run makes no GitHub call and touches nothing.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export PATH="$HOME/bin:$PATH"

REPO="dukangalex/AngelaBox"
KERNEL_REPO="dukangalex/sing-box"
TRACK=""
DRY_RUN=0
ASSUME_YES=0

for arg in "$@"; do
  case "$arg" in
    --track=beta|--track=stable) TRACK="${arg#--track=}" ;;
    --dry-run) DRY_RUN=1 ;;
    --yes|-y) ASSUME_YES=1 ;;
    -h|--help) sed -n '2,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

die() { printf '\n\033[31mABORT: %s\033[0m\n' "$1" >&2; exit 1; }
say() { printf '\n\033[1m== %s\033[0m\n' "$1"; }
run() { echo "   \$ $*"; [ "$DRY_RUN" = 1 ] || "$@"; }

[ -n "$TRACK" ] || die "usage: --track=beta|stable [--dry-run] [--yes]"

case "$TRACK" in
  beta)   PROPS="version.properties";        WORKFLOW="release-chainbox.yml"; VERSION_SUFFIX="-beta" ;;
  stable) PROPS="version-stable.properties"; WORKFLOW="release-stable.yml";   VERSION_SUFFIX="-stable" ;;
esac

prop() { grep -E "^$1=" "$PROPS" | head -1 | cut -d= -f2- | tr -d '\r'; }

# ---------------------------------------------------------------- pre-flight
say "pre-flight"

[ -f "$PROPS" ] || die "$PROPS not found"
VERSION_NAME="$(prop VERSION_NAME)"
VERSION_CODE="$(prop VERSION_CODE)"
KERNEL_TAG="$(prop KERNEL_TAG)"
KERNEL_COMMIT="$(prop KERNEL_COMMIT)"
TAG="v$VERSION_NAME"

[ -n "$VERSION_NAME" ] || die "$PROPS has no VERSION_NAME"
[ -n "$VERSION_CODE" ] || die "$PROPS has no VERSION_CODE"
[[ "$VERSION_NAME" == *"$VERSION_SUFFIX" ]] || die "$VERSION_NAME does not end in $VERSION_SUFFIX"
[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || die "VERSION_CODE '$VERSION_CODE' is not a positive integer"
[[ "$KERNEL_COMMIT" =~ ^[0-9a-f]{40}$ ]] || die "KERNEL_COMMIT '$KERNEL_COMMIT' is not a 40-hex commit"
[[ "$KERNEL_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+-chain\.[0-9]+$ ]] || die "KERNEL_TAG '$KERNEL_TAG' is not a fork tag"

if [ "$DRY_RUN" = 0 ]; then
  command -v gh >/dev/null || die "gh CLI not found"
  command -v python3 >/dev/null || die "python3 not found"
  if [ -n "${GH_TOKEN:-}" ]; then gh auth status >/dev/null 2>&1 || die "GH_TOKEN rejected"; else
    gh auth status >/dev/null 2>&1 || die "no GH_TOKEN and gh is not logged in"
  fi
else
  echo "   [dry-run] skipping the credential check (no GitHub call is made)"
fi

[ -z "$(git status --porcelain)" ] || die "working tree is not clean"

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [ "$BRANCH" != "dev" ]; then
  # A real cut must be reproducible from dev; a dry run may be rehearsed
  # anywhere (that is how this script itself gets tested before it ships).
  [ "$DRY_RUN" = 1 ] && echo "   [dry-run] on branch $BRANCH, not dev" \
    || die "cut releases from dev, not $BRANCH"
fi

git rev-parse --verify --quiet "origin/dev" >/dev/null || die "origin/dev missing (git fetch origin dev)"
git fetch -q origin dev
if [ "$(git rev-parse HEAD)" != "$(git rev-parse origin/dev)" ]; then
  [ "$DRY_RUN" = 1 ] && echo "   [dry-run] HEAD is not origin/dev" \
    || die "local dev is not origin/dev; pull first"
fi

# The release notes are what the workflow publishes; extract_release_notes.sh is
# what the workflow runs, so use it here rather than trusting the file shape.
NOTES_VERSION="${VERSION_NAME%"$VERSION_SUFFIX"}"
bash scripts/extract_release_notes.sh "$NOTES_VERSION" >/dev/null 2>&1 \
  || die "docs/RELEASE_NOTES.md has no extractable '$NOTES_VERSION' section"

# versionCode follows the repository's convention: patch * 100, plus 1 for a
# beta. Check the pin against it, then against everything already published —
# Android refuses to install a downgrade, so a stale code strands users.
EXPECTED_CODE="$(python3 - "$VERSION_NAME" <<'PYCODE'
import sys
name = sys.argv[1]
version, _, track = name.partition("-")
patch = int(version.split(".")[2])
print(patch * 100 + (1 if track.startswith("beta") else 0))
PYCODE
)"
[ "$VERSION_CODE" = "$EXPECTED_CODE" ] \
  || die "VERSION_CODE $VERSION_CODE does not match $VERSION_NAME (expected $EXPECTED_CODE)"

if [ "$DRY_RUN" = 0 ]; then
  TAGS="$(gh release list --repo "$REPO" --limit 100 --json tagName --jq '.[].tagName')"
  [ -n "$TAGS" ] || die "could not list existing releases"
  HIGHEST="$(printf '%s\n' "$TAGS" | python3 -c '
import sys
best = 0
for line in sys.stdin:
    tag = line.strip().lstrip("v")
    if not tag:
        continue
    version, _, track = tag.partition("-")
    parts = version.split(".")
    if len(parts) != 3:
        continue
    best = max(best, int(parts[2]) * 100 + (1 if track.startswith("beta") else 0))
print(best)')"
  [ "$VERSION_CODE" -gt "$HIGHEST" ] \
    || die "VERSION_CODE $VERSION_CODE is not above the highest published ($HIGHEST)"
  if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    die "release $TAG already exists"
  fi
  if git ls-remote --tags origin "refs/tags/$TAG" | grep -q .; then
    die "tag $TAG already exists on the remote"
  fi
  if gh api "repos/$KERNEL_REPO/git/commits/$KERNEL_COMMIT" --jq .sha >/dev/null 2>&1; then
    echo "   kernel commit present in $KERNEL_REPO: $KERNEL_COMMIT"
  else
    die "kernel commit $KERNEL_COMMIT not found in $KERNEL_REPO"
  fi
fi

echo
echo "Plan"
echo "  track   : $TRACK"
echo "  release : $TAG (versionCode $VERSION_CODE)"
echo "  notes   : '• **$NOTES_VERSION：**' section of docs/RELEASE_NOTES.md"
echo "  kernel  : $KERNEL_TAG @ ${KERNEL_COMMIT:0:9}"
echo "  source  : dev @ $(git rev-parse --short HEAD)"
echo "  workflow: $WORKFLOW"

# ------------------------------------------------------------------- guards
say "guard suite"
if [ "$DRY_RUN" = 1 ]; then
  echo "   [dry-run] python3 scripts/test_*.py (9 guards)"
else
  for g in override_guards china_direct_overlay dns_migrate inbound_compat \
           adblock_overlay security_guards apk_signing unit_test_whitelist pin_guards; do
    printf "   %-28s" "$g"
    python3 "scripts/test_$g.py" >/tmp/cut-guard.log 2>&1 && echo OK \
      || { echo FAIL; tail -8 /tmp/cut-guard.log; die "guard $g failed"; }
  done
fi

# ------------------------------------------------------------------ confirm
if [ "$ASSUME_YES" = 0 ]; then
  echo
  read -r -p "Type the tag ($TAG) to start the release: " typed
  [ "$typed" = "$TAG" ] || die "aborted"
fi

# --------------------------------------------------------------- dispatch
say "dispatch the signed build"
run gh workflow run "$WORKFLOW" --repo "$REPO" -f version_tag="$TAG" --ref dev
if [ "$DRY_RUN" = 1 ]; then
  echo "   [dry-run] then: gh run watch  ->  verify assets, sha256 and certificate"
  exit 0
fi

sleep 10
RUN_ID="$(gh run list --repo "$REPO" --workflow "$WORKFLOW" --limit 1 --json databaseId,headBranch \
  --jq '.[0] | select(.headBranch=="dev") | .databaseId')"
[ -n "$RUN_ID" ] || die "could not find the dispatched run"
echo "   run $RUN_ID: https://github.com/$REPO/actions/runs/$RUN_ID"

say "watch it to the end (a signed build takes ~20 min)"
gh run watch "$RUN_ID" --repo "$REPO" --exit-status >/dev/null 2>&1 \
  || { gh run view "$RUN_ID" --repo "$REPO" --log-failed 2>&1 | tail -30; die "release run $RUN_ID failed"; }

# --------------------------------------------------------------- verify it
say "verify the published artifact, not the log"
if [ "$TRACK" = stable ]; then ASSET="AngelaBox-stable-android.apk"; else ASSET="AngelaBox-android.apk"; fi
gh release view "$TAG" --repo "$REPO" --json assets,isPrerelease \
  --jq '"   tag \(.tagName) prerelease=\(.prerelease) assets=\([.assets[].name] | join(", "))"' 2>/dev/null \
  || gh release view "$TAG" --repo "$REPO" --json assets,isPrerelease \
       --jq '"   prerelease=\(.isPrerelease) assets=\([.assets[].name] | join(", "))"'

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
curl -fsSL -o "$WORK/$ASSET" "https://github.com/$REPO/releases/download/$TAG/$ASSET" \
  || die "could not download $ASSET"
curl -fsSL -o "$WORK/$ASSET.sha256" "https://github.com/$REPO/releases/download/$TAG/$ASSET.sha256" \
  || die "could not download the checksum"
( cd "$WORK" && sha256sum -c "$ASSET.sha256" >/dev/null ) || die "sha256 mismatch"
echo "   sha256 matches the sidecar"

python3 - "$WORK/$ASSET" <<'PY'
import hashlib, struct, sys, zipfile
path = sys.argv[1]
data = open(path, "rb").read()
end = data.rfind(b"PK\x05\x06")
central = struct.unpack("<I", data[end + 16 : end + 20])[0]
footer = data[central - 24 : central]
size = struct.unpack("<Q", footer[0:8])[0]
start = central - 8 - size
pairs = data[start + 8 : central - 24]
offset = 0
block = None
while offset + 12 <= len(pairs):
    length = struct.unpack("<Q", pairs[offset : offset + 8])[0]
    offset += 8
    pair_id = struct.unpack("<I", pairs[offset : offset + 4])[0]
    value = pairs[offset + 4 : offset + length]
    offset += length
    if pair_id == 0x7109871A:
        block = value
if block is None:
    sys.exit("no APK signature block")
signed_len = struct.unpack("<I", block[8:12])[0]
signed = block[12 : 12 + signed_len]
digests_len = struct.unpack("<I", signed[0:4])[0]
cursor = 4 + digests_len + 4
cert_len = struct.unpack("<I", signed[cursor : cursor + 4])[0]
cert = signed[cursor + 4 : cursor + 4 + cert_len]
print("   cert sha256:", hashlib.sha256(cert).hexdigest())
expected = "e7041217f276a7cd860b2e210f6f7d91590f263730e09fbf73bae929d6994151"
if hashlib.sha256(cert).hexdigest() != expected:
    sys.exit("SIGNING CERTIFICATE CHANGED — do not announce this release")
abis = sorted({n.split("/")[1] for n in zipfile.ZipFile(path).namelist() if n.startswith("lib/")})
print("   abis:", abis)
if abis != ["arm64-v8a"]:
    sys.exit(f"unexpected ABI set: {abis}")
PY

say "done"
echo "  $TAG is live. Smoke-test on a device before pointing stable at this kernel."
