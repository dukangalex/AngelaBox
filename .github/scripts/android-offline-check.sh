#!/usr/bin/env bash
set -euo pipefail
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:/usr/bin:/bin"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"
printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd -n isolated34 -k 'system-images;android-34;google_apis;arm64-v8a' --force
cleanup() { adb kill-server >/dev/null 2>&1 || :; if [[ -n "${emu_pid:-}" ]]; then kill "$emu_pid" 2>/dev/null || :; fi; }
trap cleanup EXIT
"$ANDROID_HOME/emulator/qemu/linux-x86_64/qemu-system-aarch64-headless" -avd isolated34 -no-window -no-audio -no-snapshot -no-boot-anim -gpu off -cores 2 -memory 2048 -qemu -machine virt > "$HOME/emulator.log" 2>&1 &
emu_pid=$!
adb start-server >/dev/null
booted=0
for i in $(seq 1 180); do
  if ! kill -0 "$emu_pid" 2>/dev/null; then tail -n 80 "$HOME/emulator.log"; exit 1; fi
  if test "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1; then booted=1; break; fi
  sleep 5
done
if test "$booted" != 1; then echo 'ARM emulator boot timeout'; tail -n 80 "$HOME/emulator.log"; exit 1; fi
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.build.version.sdk
printf 'SMOKE_RESULT=clean-emulator-boot-only\n'
