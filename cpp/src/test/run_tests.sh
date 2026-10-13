#!/usr/bin/env bash
# Build and run the pure-logic version-rule tests (cpp/src/test/cpp/
# HookRulesTest.cpp) against the shipped core/src/main/assets/game-hooks.ini.
#
# memory/HookRules.cpp is std C++ with one Android dependency (a debug log line),
# so it is fully testable off the game. This machine's desktop clang ships no
# C++ standard library, but the NDK's aarch64 clang++ does - so we cross-build a
# static binary and run it on the connected device. No game, no root, no hooks:
# the binary just parses a text file and checks its own assertions.
#
# Usage:  cpp/src/test/run_tests.sh [--desktop]
#   default   NDK aarch64 + adb (needs a device attached)
#   --desktop desktop clang++/g++ via the host/android log shim (needs a host
#             libc++/libstdc++); used automatically when no device is present
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
src="$root/cpp/src/test/cpp/HookRulesTest.cpp"
impl="$root/cpp/src/main/cpp/memory/HookRules.cpp"
ini="$root/core/src/main/assets/game-hooks.ini"
out="$root/build/hookrules_test"
mode="${1:-}"

# Resolve the Android SDK root from local.properties. A helper Node script does
# the Java-properties unescape because both sed BRE and inline shell quoting
# mangle the `\\:`-style backslashes Windows writes there.
sdk="$(node "$root/cpp/src/test/sdk_path.js" "$root/local.properties" 2>/dev/null || true)"
[ -n "$sdk" ] && [ -d "$sdk" ] || sdk=""
[ -n "$sdk" ] || sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
adb=""
[ -n "$sdk" ] && [ -x "$sdk/platform-tools/adb.exe" ] && adb="$sdk/platform-tools/adb.exe"

find_ndk() {
  [ -n "$sdk" ] || return 0
  # Newest NDK (sort -V) whose windows toolchain exists.
  while read -r tc; do
    [ -x "$tc/bin/clang++.exe" ] && [ -d "$tc/sysroot" ] && { echo "$tc"; return 0; }
  done < <(ls -1d "$sdk"/ndk/*/toolchains/llvm/prebuilt/windows-x86_64 2>/dev/null | sort -V -r)
  return 0
}

device_attached() {
  [ -n "$adb" ] && "$adb" devices 2>/dev/null | awk 'NR>1 && $2=="device"{found=1} END{exit !found}'
}

build_desktop() {
  local cxx="${CXX:-}"
  [ -n "$cxx" ] || cxx="$(command -v clang++ || command -v g++ || true)"
  [ -n "$cxx" ] || { echo "no desktop C++ compiler"; return 1; }
  echo "[build] desktop ($cxx) + host log shim"
  # shellcheck disable=SC2086
  "$cxx" -std=c++17 -Wall -Wextra -O1 \
    -I "$root/cpp/src/test/cpp/host" -I "$root/cpp/src/main/cpp" \
    "$src" "$impl" -o "$out.exe"
}

run_desktop() {
  "$out.exe" "$ini"
}

build_and_run_device() {
  local tc; tc="$(find_ndk)"
  [ -n "$tc" ] || { echo "no usable NDK toolchain under $sdk/ndk"; return 1; }
  echo "[build] NDK aarch64 ($tc)"
  # clang++.exe and adb.exe are Windows binaries; Git Bash hands them paths
  # verbatim once MSYS conversion is off. Relative paths are read against the
  # shared cwd, so cd to the repo root and keep every file argument relative -
  # an absolute `/d/...` looks like a `/d` switch to clang and like nothing to
  # adb. The toolchain lives on another drive, so that one path stays absolute
  # and Windows-form.
  export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
  local sysroot_win; sysroot_win="$(cygpath -w "$tc/sysroot")"
  cd "$root"
  rm -f build/hookrules_test
  "$tc/bin/clang++.exe" --target=aarch64-linux-android21 --sysroot="$sysroot_win" \
    -std=c++17 -Wall -Wextra -O1 -I cpp/src/main/cpp \
    cpp/src/test/cpp/HookRulesTest.cpp cpp/src/main/cpp/memory/HookRules.cpp \
    -llog -static-libstdc++ -o build/hookrules_test
  [ -x build/hookrules_test ] || [ -f build/hookrules_test ] || { echo "compiler produced no binary"; return 1; }
  echo "[run] device"
  "$adb" push build/hookrules_test /data/local/tmp/hookrules_test >/dev/null
  "$adb" push core/src/main/assets/game-hooks.ini /data/local/tmp/game-hooks.ini >/dev/null
  "$adb" shell "chmod 755 /data/local/tmp/hookrules_test; \
                /data/local/tmp/hookrules_test /data/local/tmp/game-hooks.ini; \
                rc=\$?; rm -f /data/local/tmp/hookrules_test /data/local/tmp/game-hooks.ini; \
                exit \$rc"
}

if [ "$mode" = "--desktop" ]; then
  build_desktop; run_desktop
elif device_attached; then
  build_and_run_device
else
  echo "[info] no device attached; falling back to the desktop toolchain"
  build_desktop; run_desktop
fi
