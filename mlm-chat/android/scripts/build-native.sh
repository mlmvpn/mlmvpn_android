#!/usr/bin/env bash
# Builds the core's native library for Android into core/src/main/jniLibs/<abi>/libmlmchat.so.
#
#   ANDROID_NDK_HOME=<ndk r27 or newer> bash android/scripts/build-native.sh [abi ...]
#
# Default ABIs: arm64-v8a and armeabi-v7a, the two the app ships (the Telegram fork's own native
# code is prebuilt for both since 1.2.45). Always rebuild both after a core change.
# Needs cargo-ndk (cargo install cargo-ndk) and the Rust targets (rustup target add
# aarch64-linux-android armv7-linux-androideabi). Shares core/target with the core, so the
# dependencies compiled for peer-cli are reused. The 16 KB page flags come from jni/.cargo.
# Platform 24: the lowest Android any host app installs on (MLMVPN minSdk 24).
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK}"
abis=("$@")
[ ${#abis[@]} -gt 0 ] || abis=(arm64-v8a armeabi-v7a)
targets=()
for abi in "${abis[@]}"; do targets+=(-t "$abi"); done
cd "$here/jni"
CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$here/../core/target}" \
  cargo ndk --platform 24 "${targets[@]}" -o "$here/core/src/main/jniLibs" build --release
# cargo-ndk does not always copy a rebuilt library (seen 2026-10-23): copy what cargo built, so
# the APK never ships a stale core
target_dir="${CARGO_TARGET_DIR:-$here/../core/target}"
for abi in "${abis[@]}"; do
  case "$abi" in
    arm64-v8a) triple=aarch64-linux-android ;;
    armeabi-v7a) triple=armv7-linux-androideabi ;;
    x86) triple=i686-linux-android ;;
    x86_64) triple=x86_64-linux-android ;;
    *) continue ;;
  esac
  mkdir -p "$here/core/src/main/jniLibs/$abi"
  cp "$target_dir/$triple/release/libmlmchat.so" "$here/core/src/main/jniLibs/$abi/libmlmchat.so"
done
# cargo-ndk also copies the cdylib side products of dependencies (if-watch): not needed, not shipped
rm -f "$here"/core/src/main/jniLibs/*/libif_watch-*.so
ls -l "$here"/core/src/main/jniLibs/*/libmlmchat.so
