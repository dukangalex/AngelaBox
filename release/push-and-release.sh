#!/usr/bin/env bash
# Push the audit fixes, merge them, pin the kernel, bump the version and trigger
# the signed beta release. Nothing here is run automatically: every push needs
# `--yes`, and every step is printed first.
#
#   ./release/push-and-release.sh --dry-run                  # show the plan
#   ./release/push-and-release.sh --mode=pr --yes            # recommended
#   ./release/push-and-release.sh --mode=direct --yes        # straight onto dev
#
# Prerequisites: gh (authenticated with repo+workflow scope), a clean tree, and
# a sibling clone of dukangalex/sing-box at $KERNEL_REPO (default ../sing-box).
set -euo pipefail

MODE="pr"
VERSION="1.0.128-beta"
VERSION_CODE="12801"
KERNEL_TAG="v1.15.0-chain.8"
APP_BRANCH="audit/security-fixes"
KERNEL_BRANCH="audit/chain-udp-fail-closed"
KERNEL_PATCH=""
DRY_RUN=0
CONFIRMED=0

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KERNEL_REPO="${KERNEL_REPO:-$ROOT/../sing-box}"

usage() { sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

for arg in "$@"; do
  case "$arg" in
    --mode=pr|--mode=direct) MODE="${arg#--mode=}" ;;
    --version=*) VERSION="${arg#--version=}" ;;
    --version-code=*) VERSION_CODE="${arg#--version-code=}" ;;
    --kernel-tag=*) KERNEL_TAG="${arg#--kernel-tag=}" ;;
    --kernel-repo=*) KERNEL_REPO="${arg#--kernel-repo=}" ;;
    --kernel-patch=*) KERNEL_PATCH="${arg#--kernel-patch=}" ;;
    --dry-run) DRY_RUN=1 ;;
    --yes) CONFIRMED=1 ;;
    -h|--help) usage 0 ;;
    *) echo "unknown argument: $arg" >&2; usage 1 ;;
  esac
done

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
run()  {
  if [ "$DRY_RUN" = 1 ]; then printf '   [dry-run] %s\n' "$*"; return 0; fi
  printf '   $ %s\n' "$*"
  "$@"
}
die()  { printf '\n\033[31mABORT: %s\033[0m\n' "$*" >&2; exit 1; }
need_yes() {
  [ "$DRY_RUN" = 1 ] && return 0
  [ "$CONFIRMED" = 1 ] && return 0
  die "this step pushes to GitHub; re-run with --yes (or --dry-run to see the plan)"
}

# ---------------------------------------------------------------- pre-flight
say "pre-flight"
cd "$ROOT"

command -v gh   >/dev/null || die "gh CLI not found"
command -v git  >/dev/null || die "git not found"
gh auth status >/dev/null 2>&1 || die "gh is not authenticated (needs repo+workflow scope)"
[ -z "$(git status --porcelain)" ] || die "working tree is not clean — commit or stash first"
[ "$(git rev-parse --abbrev-ref HEAD)" = "$APP_BRANCH" ] || die "expected branch $APP_BRANCH, on $(git rev-parse --abbrev-ref HEAD)"
git rev-parse --verify --quiet "$APP_BRANCH" >/dev/null || die "branch $APP_BRANCH missing"

# The release job refuses a version without a changelog section; catch it here.
bash scripts/extract_release_notes.sh "${VERSION%-beta}" >/dev/null \
  || die "docs/RELEASE_NOTES.md has no section for ${VERSION%-beta}"
grep -q "^VERSION_NAME=$VERSION$" version.properties \
  && die "version.properties already says $VERSION — bump the version instead of re-releasing"

case "$VERSION" in *-beta) ;; *) die "this script releases the beta track (*-beta)";; esac
[[ "$KERNEL_TAG" =~ ^v[0-9] ]] || die "kernel tag must look like v1.15.0-chain.8"

say "running the guard suite before touching the remote"
if [ "$DRY_RUN" = 1 ]; then echo "   [dry-run] python3 scripts/test_*.py (8 guards)"; else
  for g in override_guards china_direct_overlay dns_migrate inbound_compat \
           adblock_overlay security_guards apk_signing unit_test_whitelist; do
    printf '   %-28s ' "$g"
    python3 "scripts/test_$g.py" >/tmp/ab-guard.log 2>&1 && echo OK || { echo FAIL; tail -5 /tmp/ab-guard.log; die "guard $g failed"; }
  done
fi

cat <<PLAN

Plan
  app      : ${APP_BRANCH} -> dev (${MODE}) -> commit "chore(release): bump to ${VERSION}" -> push
  kernel   : ${KERNEL_BRANCH} -> chain-dev, tag ${KERNEL_TAG}, pin its SHA into version.properties
  release  : gh workflow run release-chainbox.yml -f version_tag=v${VERSION}
  ships    : AngelaBox-android.apk + .sha256, CN=ChainBox, arm64 only

PLAN

# ------------------------------------------------------------------- kernel
say "kernel: push the chain UDP fix and merge it into chain-dev"
[ -d "$KERNEL_REPO/.git" ] || die "kernel clone not found at $KERNEL_REPO (pass --kernel-repo=…)"
cd "$KERNEL_REPO"
git rev-parse --verify --quiet "$KERNEL_BRANCH" >/dev/null \
  || die "branch $KERNEL_BRANCH missing in $KERNEL_REPO — apply patches/0001-chain-refuse-UDP… first"

need_yes
run git push -u origin "$KERNEL_BRANCH"
if [ "$MODE" = "pr" ]; then
  run gh pr create --repo dukangalex/sing-box --base chain-dev --head "$KERNEL_BRANCH" \
      --title "chain: refuse UDP on the chain outbound" \
      --body "detour only routes the TCP dial; the landing hop's ListenPacket ignores it, so UDP left from the wrong address. Refuse instead (fail-closed). Network() deliberately keeps reporting the entry hop so urltest cannot pick an unchained member for UDP. Adds protocol/chain/outbound_test.go."
  run gh pr merge --repo dukangalex/sing-box "$KERNEL_BRANCH" --merge
else
  run git fetch origin chain-dev
  run git checkout chain-dev
  run git merge --no-ff "$KERNEL_BRANCH" -m "merge: chain refuses UDP (audit F-01)"
  run git push origin chain-dev
fi

say "kernel: tag the release the app will pin"
if [ "$DRY_RUN" = 1 ]; then
  KERNEL_COMMIT="<chain-dev head after the merge>"
  echo "   [dry-run] git fetch origin chain-dev && git tag $KERNEL_TAG <head> && git push -f origin $KERNEL_TAG"
else
  git fetch origin chain-dev
  KERNEL_COMMIT="$(git rev-parse origin/chain-dev)"
  [[ "$KERNEL_COMMIT" =~ ^[0-9a-f]{40}$ ]] || die "bad kernel commit: $KERNEL_COMMIT"
  git tag -f "$KERNEL_TAG" "$KERNEL_COMMIT" >/dev/null
  git push -f origin "$KERNEL_TAG"
  echo "   pinned kernel commit: $KERNEL_COMMIT"
fi
git remote set-head origin --auto >/dev/null 2>&1 || true

# ---------------------------------------------------------------------- app
say "app: merge the fixes into dev"
cd "$ROOT"
need_yes
run git push -u origin "$APP_BRANCH"
if [ "$MODE" = "pr" ]; then
  run gh pr create --repo dukangalex/AngelaBox --base dev --head "$APP_BRANCH" \
      --title "security: audit fixes (F-01…F-09) for 1.0.128-beta" \
      --body "Four commits from the 2026-10-24 audit: loopback-only inbounds, pinned rule-set tags, filtered SSRF fallback, arm64-only ABI, narrower import filter, pinned actions, repo hygiene, docs. Details in audit/AngelaBox-修复说明.md."
  echo "   waiting for CI on the PR before merging…"
  run gh pr checks --repo dukangalex/AngelaBox "$APP_BRANCH" --watch
  run gh pr merge --repo dukangalex/AngelaBox "$APP_BRANCH" --merge
else
  run git fetch origin dev
  run git push origin "$APP_BRANCH:dev"
fi
run git fetch origin dev
run git checkout dev
run git pull --ff-only origin dev

say "app: pin the kernel and bump to $VERSION"
if [ "$DRY_RUN" = 1 ]; then
  echo "   [dry-run] version.properties: VERSION_CODE=$VERSION_CODE VERSION_NAME=$VERSION"
  echo "   [dry-run] version.properties: KERNEL_TAG=$KERNEL_TAG KERNEL_COMMIT=<chain-dev head>"
else
  python3 - "$VERSION" "$VERSION_CODE" "$KERNEL_TAG" "$KERNEL_COMMIT" <<'PY'
import re, sys, pathlib
version, code, ktag, kcommit = sys.argv[1:5]
p = pathlib.Path("version.properties")
text = p.read_text()
for key, value in (("VERSION_CODE", code), ("VERSION_NAME", version),
                   ("KERNEL_TAG", ktag), ("KERNEL_COMMIT", kcommit)):
    text, n = re.subn(rf"^{key}=.*$", f"{key}={value}", text, flags=re.M)
    assert n == 1, f"{key} not found in version.properties"
p.write_text(text)
print(text.strip())
PY
  run git add version.properties
  run git commit -m "chore(release): bump to $VERSION ($VERSION_CODE) and pin $KERNEL_TAG"
  run git push origin dev
fi

# ------------------------------------------------------------------ release
say "release: dispatch the signed beta build"
need_yes
run gh workflow run release-chainbox.yml --repo dukangalex/AngelaBox -f version_tag="v$VERSION"
sleep 5
run gh run list --repo dukangalex/AngelaBox --workflow release-chainbox.yml --limit 1

cat <<'AFTER'

After the run finishes (the workflow refuses to publish if any gate fails):

  gh release view "v$VERSION" --repo dukangalex/AngelaBox --json assets
  # verify the shipped APK locally:
  curl -LO "https://github.com/dukangalex/AngelaBox/releases/download/v$VERSION/AngelaBox-android.apk"
  sha256sum -c AngelaBox-android.apk.sha256

  # expected signing certificate (must stay CN=ChainBox):
  #   e7041217f276a7cd860b2e210f6f7d91590f263730e09fbf73bae929d6994151

Smoke-test on a device before promoting anything to the stable track:
  1. chain on, TCP works and the IP check shows the LANDING node (F-01 regression);
  2. chain on, a UDP echo/game now fails instead of silently bypassing the chain;
  3. import a profile carrying "listen": "0.0.0.0" -> notice says it was rebound,
     and another device on the LAN cannot reach that port (F-02);
  4. a normal profile still starts and routes as before (F-02 regression).
AFTER
