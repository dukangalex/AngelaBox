#!/usr/bin/env bash
set -euxo pipefail
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:/usr/bin:/bin"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export LD_LIBRARY_PATH="$ANDROID_HOME/emulator/lib64:$ANDROID_HOME/emulator/lib64/qt/lib"
cd "$HOME"
export ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"
trap 'rc=$?; echo "CHECK_EXIT=$rc"; test ! -f "$HOME/emulator.log" || tail -n 100 "$HOME/emulator.log"' EXIT
printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd -n isolated34 -k 'system-images;android-34;google_apis;x86_64' --force
cleanup() { rc=$?; echo "CHECK_EXIT=$rc"; if [ "$rc" -ne 0 ]; then echo "--- isolated crash buffer ---"; adb logcat -b crash -d -v threadtime 2>/dev/null | tail -n 300 || :; echo "--- targeted app and runtime logs ---"; adb logcat -d -v threadtime -s AndroidRuntime:E DEBUG:F libc:F ActivityTaskManager:I ActivityManager:I 2>/dev/null | tail -n 250 || :; echo "--- isolated app exit info ---"; adb shell dumpsys activity exit-info io.chainbox.app 2>/dev/null | tail -n 120 || :; fi; test ! -f "$HOME/emulator.log" || tail -n 30 "$HOME/emulator.log"; adb kill-server >/dev/null 2>&1 || :; if [[ -n "${emu_pid:-}" ]]; then kill "$emu_pid" 2>/dev/null || :; fi; }
trap cleanup EXIT
"$ANDROID_HOME/emulator/emulator" -avd isolated34 -no-window -no-audio -no-snapshot -no-boot-anim -no-metrics -accel auto -gpu swiftshader -cores 2 -memory 2048 > "$HOME/emulator.log" 2>&1 &
emu_pid=$!
adb start-server >/dev/null
booted=0
for i in $(seq 1 180); do
  if ! kill -0 "$emu_pid" 2>/dev/null; then tail -n 80 "$HOME/emulator.log"; exit 1; fi
  if test "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1; then booted=1; break; fi
  sleep 5
done
if test "$booted" != 1; then echo 'Translated x86_64 emulator boot timeout'; tail -n 80 "$HOME/emulator.log"; exit 1; fi
abi="$(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
bridge="$(adb shell getprop ro.dalvik.vm.native.bridge | tr -d '\r')"
printf 'EMULATOR_ABI=%s SDK=%s NATIVE_BRIDGE=%s\n' "$abi" "$sdk" "$bridge"
[[ -n "$bridge" && "$bridge" != 0 ]]
[[ ",$abi," == *,arm64-v8a,* ]]
[[ "$sdk" == 34 ]]
echo 'dec184878c4ba91b1e11919250b813865552df2d991c92441dda0b54f18dffa7  /home/wabi_trial/candidate.apk' | sha256sum -c -
adb install --abi arm64-v8a /home/wabi_trial/candidate.apk
adb shell pm path io.chainbox.app
adb shell monkey -p io.chainbox.app -c android.intent.category.LAUNCHER 1
sleep 10
adb shell pidof io.chainbox.app
printf 'SMOKE_RESULT=exact-arm64-apk-installed-and-launched-offline-via-native-translation\n'
