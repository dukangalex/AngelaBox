#!/usr/bin/env bash
# Cross-compile AngelaBox Windows CLI from the audited chain-dev kernel.
# Run from the kernel repository root.
set -euo pipefail

KERNEL_TAG="${KERNEL_TAG:?KERNEL_TAG required}"
APP_VERSION="${APP_VERSION:?APP_VERSION required}"
README_WINDOWS="${README_WINDOWS:?README_WINDOWS required}"
DIST="${DIST:-dist/windows}"
echo "$KERNEL_TAG" | grep -Eq '^v[0-9]'
echo "$APP_VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([-.][A-Za-z0-9.]+)?$'
test -f "$README_WINDOWS"
test -f cmd/sing-box/main.go
test -f release/DEFAULT_BUILD_TAGS_WINDOWS
test -f release/DEFAULT_BUILD_TAGS_OTHERS
test -f release/LDFLAGS
test -f LICENSE

VERSION="${KERNEL_TAG#v}"
LDFLAGS_SHARED="$(tr -d '\r' < release/LDFLAGS)"
TAGS_WIN="$(tr -d '\r' < release/DEFAULT_BUILD_TAGS_WINDOWS)"
TAGS_OTHERS="$(tr -d '\r' < release/DEFAULT_BUILD_TAGS_OTHERS)"
rm -rf "$DIST"
mkdir -p "$DIST"

build_one() {
  local arch="$1"
  local tags="$2"
  local dir="AngelaBox-v${APP_VERSION}-windows-${arch}"
  echo "building GOOS=windows GOARCH=${arch}"
  CGO_ENABLED=0 GOOS=windows GOARCH="$arch" go build -trimpath \
    -o "${DIST}/sing-box.exe" \
    -tags "$tags" \
    -ldflags "-X github.com/sagernet/sing-box/constant.Version=${VERSION} ${LDFLAGS_SHARED} -s -w -buildid=" \
    ./cmd/sing-box
  test -s "${DIST}/sing-box.exe"
  mkdir -p "${DIST}/${dir}"
  mv "${DIST}/sing-box.exe" "${DIST}/${dir}/sing-box.exe"
  cp LICENSE "${DIST}/${dir}/LICENSE"
  cp "$README_WINDOWS" "${DIST}/${dir}/README.txt"
  (cd "$DIST" && zip -r -9 "${dir}.zip" "$dir")
  rm -rf "${DIST}/${dir}"
  (cd "$DIST" && sha256sum "${dir}.zip" > "${dir}.zip.sha256")
}

build_one amd64 "$TAGS_WIN"
build_one arm64 "$TAGS_WIN"
build_one 386 "$TAGS_OTHERS"
ls -lah "$DIST"
