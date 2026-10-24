#!/usr/bin/env bash
# Push the audit fixes, merge them through PRs, tag the kernel and publish.
#
#   ./release/push-and-release.sh --dry-run              # print the whole plan
#   ./release/push-and-release.sh --stage=beta --yes     # 1.0.128-beta now
#   ./release/push-and-release.sh --stage=stable --yes   # 1.0.129-stable after the soak
#   ./release/push-and-release.sh --stage=all --yes      # both in one go
#
# Needs GH_TOKEN (or a gh login) with, on BOTH repositories: Contents:rw,
# Pull requests:rw, Actions:rw, Workflows:rw. The token is read from the
# environment and never written to disk.
#
# Kernel policy (decided 2026-10-24): the release tag is cut from the kernel
# commit the shipped app already uses (9c0a0c7) plus the UDP fix only, so a
# security release does not drag the official v1.15.0-alpha.11 merge in.
# chain-dev keeps the fix through its own PR and is otherwise untouched.
# HISTORICAL — written for the 1.0.128-beta / 1.0.129-stable security release
# on kernel v1.15.0-chain.8, and it hard-codes that tag, that kernel commit and
# that stable version. It is kept as the record of how that release was cut.
# For any new release use release/cut-release.sh, which reads the pin from
# version.properties / version-stable.properties.
set -euo pipefail

STAGE="beta"
BETA_VERSION="1.0.128-beta"
BETA_CODE="12801"
STABLE_VERSION="1.0.129-stable"
STABLE_CODE="12900"
KERNEL_TAG="v1.15.0-chain.8"
KERNEL_BASE="9c0a0c714d7eb113cb246916292ba9438970438c"
KERNEL_REL_BRANCH="release/kernel-1.0.128"
APP_BRANCH="audit/security-fixes"
KERNEL_FIX_BRANCH="audit/chain-udp-fail-closed"
DRY_RUN=0
CONFIRMED=0

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KERNEL_REPO="${KERNEL_REPO:-$ROOT/../sing-box}"
APP_REPO="dukangalex/AngelaBox"
KERNEL_REMOTE="dukangalex/sing-box"

usage() { sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

for arg in "$@"; do
  case "$arg" in
    --stage=beta|--stage=stable|--stage=all) STAGE="${arg#--stage=}" ;;
    --kernel-repo=*) KERNEL_REPO="${arg#--kernel-repo=}" ;;
    --kernel-tag=*) KERNEL_TAG="${arg#--kernel-tag=}" ;;
    --dry-run) DRY_RUN=1 ;;
    --yes) CONFIRMED=1 ;;
    -h|--help) usage 0 ;;
    *) echo "unknown argument: $arg" >&2; usage 1 ;;
  esac
done

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
run() {
  if [ "$DRY_RUN" = 1 ]; then printf '   [dry-run] %s\n' "$*"; return 0; fi
  printf '   $ %s\n' "$*"
  "$@"
}
die() { printf '\n\033[31mABORT: %s\033[0m\n' "$*" >&2; exit 1; }
need_yes() {
  [ "$DRY_RUN" = 1 ] && return 0
  [ "$CONFIRMED" = 1 ] && return 0
  die "this step talks to GitHub; re-run with --yes (or --dry-run to see the plan)"
}

# ---------------------------------------------------------------- pre-flight
say "pre-flight"
cd "$ROOT"
export PATH="$HOME/bin:$PATH"

command -v gh  >/dev/null || die "gh CLI not found"
command -v git >/dev/null || die "git not found"
if [ "$DRY_RUN" = 1 ]; then
  echo "   [dry-run] skipping the credential check (no GitHub call is made)"
else
  if [ -z "${GH_TOKEN:-}" ]; then
    gh auth status >/dev/null 2>&1 || die "no GH_TOKEN and gh is not logged in"
  fi
  gh auth status >/dev/null 2>&1 || die "credentials rejected"
fi
[ -z "$(git status --porcelain)" ] || die "working tree is not clean"

wants_beta=0; wants_stable=0
case "$STAGE" in beta) wants_beta=1 ;; stable) wants_stable=1 ;; all) wants_beta=1; wants_stable=1 ;; esac

if [ "$wants_beta" = 1 ]; then
  git rev-parse --verify --quiet "$APP_BRANCH" >/dev/null || die "branch $APP_BRANCH missing"
  bash scripts/extract_release_notes.sh "${BETA_VERSION%-beta}" >/dev/null \
    || die "docs/RELEASE_NOTES.md has no section for ${BETA_VERSION%-beta}"
  grep -q "^VERSION_NAME=$BETA_VERSION$" version.properties \
    && die "version.properties already says $BETA_VERSION"
  git ls-remote --tags origin "refs/tags/v$BETA_VERSION" | grep -q . \
    && die "tag v$BETA_VERSION already exists"
fi
if [ "$wants_stable" = 1 ]; then
  [ -f release/notes-1.0.129-stable.md ] || die "release/notes-1.0.129-stable.md missing"
  grep -q "^• \*\*${STABLE_VERSION%-stable}：\*\*" release/notes-1.0.129-stable.md \
    || die "stable notes have the wrong heading"
  git ls-remote --tags origin "refs/tags/v$STABLE_VERSION" | grep -q . \
    && die "tag v$STABLE_VERSION already exists"
  grep -q "^VERSION_NAME=$STABLE_VERSION$" version-stable.properties \
    && die "version-stable.properties already says $STABLE_VERSION"
fi

say "guard suite"
if [ "$DRY_RUN" = 1 ]; then
  echo "   [dry-run] python3 scripts/test_*.py (9 guards)"
else
  for g in override_guards china_direct_overlay dns_migrate inbound_compat \
           adblock_overlay security_guards apk_signing unit_test_whitelist pin_guards; do
    printf '   %-28s ' "$g"
    python3 "scripts/test_$g.py" >/tmp/ab-guard.log 2>&1 && echo OK \
      || { echo FAIL; tail -5 /tmp/ab-guard.log; die "guard $g failed"; }
  done
fi

cat <<PLAN

Plan (stage: ${STAGE})
  kernel  : ${KERNEL_BASE:0:7} + UDP fix -> ${KERNEL_REL_BRANCH}, tag ${KERNEL_TAG} THERE
            (chain-dev untouched by the tag; the fix reaches it via its own PR)
$( [ "$wants_beta" = 1 ] && echo "  beta    : PR ${APP_BRANCH} -> dev (CI gate) -> merge -> pin kernel -> ${BETA_VERSION} (${BETA_CODE}) -> dispatch release-chainbox.yml" )
$( [ "$wants_stable" = 1 ] && echo "  stable  : add 1.0.129 notes -> pin kernel -> ${STABLE_VERSION} (${STABLE_CODE}) -> dispatch release-stable.yml" )
  artifacts: AngelaBox-android.apk / AngelaBox-stable-android.apk + .sha256, CN=ChainBox, arm64
PLAN

# ------------------------------------------------------------------- kernel
if [ "$wants_beta" = 1 ] || [ "$wants_stable" = 1 ]; then
  say "kernel: cut the release branch from the shipped commit and tag it"
  [ -d "$KERNEL_REPO/.git" ] || die "kernel clone not found at $KERNEL_REPO"
  cd "$KERNEL_REPO"
  git rev-parse --verify --quiet "$KERNEL_FIX_BRANCH" >/dev/null \
    || die "branch $KERNEL_FIX_BRANCH missing (apply patches/0001-chain-refuse-UDP… first)"
  git merge-base --is-ancestor "$KERNEL_BASE" "$KERNEL_FIX_BRANCH" \
    || die "$KERNEL_FIX_BRANCH is not based on $KERNEL_BASE"
  git branch -f "$KERNEL_REL_BRANCH" "$KERNEL_FIX_BRANCH"

  if [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
    echo "   deepening the kernel clone so the tag can be pushed"
    [ "$DRY_RUN" = 1 ] || git fetch --unshallow origin
  fi

  need_yes
  run git push -u origin "$KERNEL_REL_BRANCH"
  # Annotated tag, matching the style of the existing v1.15.0-chain.* tags.
  run git tag -f -a "$KERNEL_TAG" -m "chain: $(git rev-parse --short "$KERNEL_REL_BRANCH") + UDP fail-closed (audit F-01)" "$KERNEL_REL_BRANCH"
  run git push -f origin "refs/tags/$KERNEL_TAG"
  if [ "$DRY_RUN" = 0 ]; then
    KERNEL_COMMIT="$(git rev-parse "$KERNEL_REL_BRANCH")"
    [[ "$KERNEL_COMMIT" =~ ^[0-9a-f]{40}$ ]] || die "bad kernel commit: $KERNEL_COMMIT"
    echo "   pinned kernel commit: $KERNEL_COMMIT"
  else
    KERNEL_COMMIT="<head of $KERNEL_REL_BRANCH>"
  fi

  # The fix must not be lost by the next chain-dev sync, but merging it there
  # must not move the release tag onto alpha.11.
  # Idempotent: --stage=stable re-enters this block after --stage=beta already
  # merged the PR, and gh pr create aborts when there is nothing left to merge.
  existing_kernel_pr=""
  if [ "$DRY_RUN" = 0 ]; then
    existing_kernel_pr="$(gh pr list --repo "$KERNEL_REMOTE" --head "$KERNEL_REL_BRANCH" \
      --state all --json number,state --jq '.[0] | "\(.number) \(.state)"' 2>/dev/null || true)"
  fi
  if [ -n "$existing_kernel_pr" ]; then
    echo "   kernel PR already exists ($existing_kernel_pr); not recreating it"
  else
  run gh pr create --repo "$KERNEL_REMOTE" --base chain-dev --head "$KERNEL_REL_BRANCH" \
      --title "chain: refuse UDP on the chain outbound" \
      --body "detour only routes the TCP dial; the landing hop's ListenPacket ignores it, so UDP left from the wrong address while the UI said the landing node was the exit. Refuse instead (fail-closed). Network() deliberately keeps reporting the entry hop so urltest cannot pick an unchained member for UDP. Adds protocol/chain/outbound_test.go.

Note: ${KERNEL_TAG} is tagged on this branch, which is based on ${KERNEL_BASE:0:7} (the commit 1.0.x ships), NOT on the current chain-dev head — a security release must not silently adopt the official alpha.11 merge. Chain tests: \`go test ./protocol/chain/ ./option/\`."
  run gh pr merge --repo "$KERNEL_REMOTE" "$KERNEL_REL_BRANCH" --merge
  fi
  cd "$ROOT"
fi

# ---------------------------------------------------------------------- app
if [ "$wants_beta" = 1 ]; then
  say "app: merge the fixes into dev through a PR"
  need_yes
  git checkout "$APP_BRANCH"
  run git push -u origin "$APP_BRANCH"
  run gh pr create --repo "$APP_REPO" --base dev --head "$APP_BRANCH" \
      --title "security: audit fixes F-01…F-09 for ${BETA_VERSION}" \
      --body "Five commits from the 2026-10-24 audit: chain UDP fail-closed (app side: docs), loopback-only inbounds, pinned rule-set tags, filtered SSRF fallback, arm64-only ABI, narrower import filter, pinned actions, repo hygiene, 1.0.128 notes. Per-item write-up: audit/AngelaBox-修复说明.md.

Ships with kernel ${KERNEL_TAG} = ${KERNEL_BASE:0:7} + the UDP fix."
  # Checks take a moment to register after the PR opens; --watch errors out on
  # "no checks reported" if it starts too early. Wait for them to appear first.
  if [ "$DRY_RUN" = 1 ]; then
    echo "   [dry-run] wait for CI checks to register, then gh pr checks --watch --fail-fast"
  else
    # gh pr checks exits non-zero while checks are pending, so test for output
    # rather than for exit status.
    for attempt in $(seq 1 40); do
      if [ -n "$(gh pr checks "$APP_BRANCH" --repo "$APP_REPO" 2>/dev/null)" ]; then break; fi
      echo "   waiting for CI checks to register ($attempt/40)…"
      sleep 15
    done
    gh pr checks "$APP_BRANCH" --repo "$APP_REPO" --watch --fail-fast --interval 20
  fi
  run gh pr merge --repo "$APP_REPO" "$APP_BRANCH" --merge
  run git fetch origin dev
  # Local dev can be ahead of origin/dev (the audit commits were authored there
  # before the branch was cut). They are on the remote branch by now, so a hard
  # sync to origin/dev is the only thing that cannot silently re-publish them.
  run git checkout -B dev origin/dev

  say "app: pin the kernel and bump beta to $BETA_VERSION"
  if [ "$DRY_RUN" = 1 ]; then
    echo "   [dry-run] version.properties: VERSION_NAME=$BETA_VERSION VERSION_CODE=$BETA_CODE KERNEL_TAG=$KERNEL_TAG KERNEL_COMMIT=$KERNEL_COMMIT"
  else
    python3 - version.properties "$BETA_VERSION" "$BETA_CODE" "$KERNEL_TAG" "$KERNEL_COMMIT" <<'PY'
import re, sys, pathlib
path, version, code, ktag, kcommit = sys.argv[1:6]
p = pathlib.Path(path); text = p.read_text()
for key, value in (("VERSION_CODE", code), ("VERSION_NAME", version),
                   ("KERNEL_TAG", ktag), ("KERNEL_COMMIT", kcommit)):
    text, n = re.subn(rf"^{key}=.*$", f"{key}={value}", text, flags=re.M)
    assert n == 1, f"{key} missing from {path}"
p.write_text(text)
print(text.strip())
PY
    run git add version.properties
    run git commit -m "chore(release): bump to $BETA_VERSION ($BETA_CODE) and pin $KERNEL_TAG"
    run git push origin dev
  fi

  say "release: dispatch the signed beta build"
  need_yes
  run gh workflow run release-chainbox.yml --repo "$APP_REPO" -f version_tag="v$BETA_VERSION"
  sleep 5
  run gh run list --repo "$APP_REPO" --workflow release-chainbox.yml --limit 1
fi

# ------------------------------------------------------------------- stable
if [ "$wants_stable" = 1 ]; then
  say "stable: add the 1.0.129 changelog section"
  need_yes
  run git fetch origin dev
  run git checkout dev
  run git pull --ff-only origin dev
  if [ "$DRY_RUN" = 1 ]; then
    echo "   [dry-run] prepend release/notes-1.0.129-stable.md to docs/RELEASE_NOTES.md"
  else
    grep -q "• \*\*${STABLE_VERSION%-stable}：\*\*" docs/RELEASE_NOTES.md \
      && die "docs/RELEASE_NOTES.md already has ${STABLE_VERSION%-stable}"
    cat release/notes-1.0.129-stable.md docs/RELEASE_NOTES.md > /tmp/notes.new
    mv /tmp/notes.new docs/RELEASE_NOTES.md
    bash scripts/extract_release_notes.sh "${STABLE_VERSION%-stable}" >/dev/null \
      || die "the new section does not extract"
  fi

  say "stable: pin the kernel and bump to $STABLE_VERSION"
  if [ "$DRY_RUN" = 1 ]; then
    echo "   [dry-run] version-stable.properties: VERSION_NAME=$STABLE_VERSION VERSION_CODE=$STABLE_CODE KERNEL_TAG=$KERNEL_TAG KERNEL_COMMIT=$KERNEL_COMMIT"
    echo "   [dry-run] commit + push to dev"
  else
    python3 - version-stable.properties "$STABLE_VERSION" "$STABLE_CODE" "$KERNEL_TAG" "$KERNEL_COMMIT" <<'PY'
import re, sys, pathlib
path, version, code, ktag, kcommit = sys.argv[1:6]
p = pathlib.Path(path); text = p.read_text()
for key, value in (("VERSION_CODE", code), ("VERSION_NAME", version),
                   ("KERNEL_TAG", ktag), ("KERNEL_COMMIT", kcommit)):
    text, n = re.subn(rf"^{key}=.*$", f"{key}={value}", text, flags=re.M)
    assert n == 1, f"{key} missing from {path}"
p.write_text(text)
print(text.strip())
PY
    git add docs/RELEASE_NOTES.md version-stable.properties
    git commit -m "chore(release): $STABLE_VERSION ($STABLE_CODE), pin $KERNEL_TAG"
    git push origin dev
  fi

  say "release: dispatch the signed stable build"
  need_yes
  run gh workflow run release-stable.yml --repo "$APP_REPO" -f version_tag="v$STABLE_VERSION"
  sleep 5
  run gh run list --repo "$APP_REPO" --workflow release-stable.yml --limit 1
fi

cat <<'AFTER'

After each workflow finishes (every gate re-verifies signing, SHA-256 and the
release certificate, and refuses to overwrite an existing tag or release):

  gh run watch --repo dukangalex/AngelaBox
  gh release view "v$VERSION" --repo dukangalex/AngelaBox --json assets,body
  curl -LO "https://github.com/dukangalex/AngelaBox/releases/download/v$VERSION/AngelaBox-android.apk"
  sha256sum -c AngelaBox-android.apk.sha256
  # expected signing certificate, unchanged: e7041217…994151 (CN=ChainBox)

Device smoke test before the stable stage:
  1. chain on -> TCP works AND the IP check shows the LANDING node;
  2. chain on -> a UDP probe fails instead of silently bypassing the chain;
  3. import a profile with "listen": "0.0.0.0" -> the notice says it was
     rebound, and another LAN device cannot reach that port;
  4. a normal profile still starts and routes as before.
AFTER
