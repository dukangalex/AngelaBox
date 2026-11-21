#!/usr/bin/env bash
# Reproduction only: no tag, signing, version selection, or publishing.
set -euo pipefail
props=$(realpath "${1:?candidate properties}")
kernel=$(realpath "${2:?kernel checkout}")
out=$(realpath -m "${3:?separate output directory}")
read_prop() {
  local key=$1
  test "$(grep -c "^$key=" "$props")" = 1
  grep "^$key=" "$props" | cut -d= -f2-
}
commit=$(read_prop KERNEL_COMMIT)
go_version=$(read_prop GO_VERSION)
mobile=$(read_prop GOMOBILE_VERSION)
ndk=$(read_prop ANDROID_NDK_VERSION)
api=$(read_prop ANDROID_API)
tags=$(read_prop BUILD_TAGS)
[[ "$commit" =~ ^[0-9a-f]{40}$ ]]
[[ "$go_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
[[ "$mobile" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]
[[ "$ndk" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
[[ "$api" = 24 ]]
[[ "$tags" =~ ^[a-z0-9_,]+$ ]]
test "$(git -C "$kernel" rev-parse HEAD)" = "$commit"
test -z "$(git -C "$kernel" status --porcelain)"
test "$(go env GOVERSION)" = "go$go_version"
export ANDROID_HOME="${ANDROID_HOME:?Android SDK required}"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$ndk"
test -d "$ANDROID_NDK_HOME"
mkdir -p "$out"
{
  git -C "$kernel" rev-parse HEAD HEAD^{tree}
  go version
  go version -m "$(command -v gomobile)"
  go version -m "$(command -v gobind)"
  javac -version
  cat "$ANDROID_NDK_HOME/source.properties"
} > "$out/toolchain.txt" 2>&1
grep -Eq "mod[[:space:]]+github.com/sagernet/gomobile[[:space:]]+$mobile([[:space:]]|$)" "$out/toolchain.txt"
cp "$props" "$out/kernel-candidate.properties"
cd "$kernel"
export CGO_LDFLAGS_ALLOW='-l:libcronet\.a'
gomobile bind -v -o "$out/libbox-native-close-arm64-api24.aar" -target android/arm64 -androidapi "$api" -javapkg=io.nekohasekai -libname=box -trimpath -buildvcs=false -ldflags='-X github.com/sagernet/sing-box/constant.Version=native-close-candidate -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid=' -tags "$tags" ./experimental/libbox
unzip -t "$out/libbox-native-close-arm64-api24.aar" > "$out/archive-integrity.txt"
unzip -p "$out/libbox-native-close-arm64-api24.aar" jni/arm64-v8a/libbox.so > "$out/libbox.so"
readelf -h -lW "$out/libbox.so" > "$out/elf.txt"
python3 - "$out/elf.txt" <<'PY'
import pathlib, re, sys
s = pathlib.Path(sys.argv[1]).read_text()
assert 'AArch64' in s and 'ELF64' in s
loads = [l.split() for l in s.splitlines() if l.strip().startswith('LOAD ')]
assert loads
for l in loads:
    off, vaddr, align = int(l[1],16), int(l[2],16), int(l[-1],16)
    assert align >= 16384 and (vaddr-off) % 16384 == 0
PY
sha256sum "$out/libbox-native-close-arm64-api24.aar" "$out/libbox.so" > "$out/SHA256SUMS"
printf '%s\n' 'Validation only. Does not select this kernel for releases or establish JNI/VPN/device acceptance.' > "$out/README.txt"
