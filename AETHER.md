# Aether on Android

This document explains how the Aether censorship-circumvention engine (MASQUE /
WireGuard / WARP-in-WARP) is bundled and run in this Android app. The same Rust binary
that drives a desktop `aether.exe` build (from the private companion desktop project —
not part of this repository) is shipped inside the APK and spawned by the Kotlin layer
at runtime. Everything referenced below with an `android/` prefix is inside this repo;
the handful of source paths without that prefix (`core/aether/aether/src/main.rs`,
`aether-src/build-android.sh`, `aether-manager.js`) belong to that separate project and
are only listed here for readers who have both checked out side by side.

## What lives where

| File                                                                  | Role                           |
|-----------------------------------------------------------------------|--------------------------------|
| `core/aether/aether/src/main.rs` & friends                           | The Rust binary source         |
| `android/app/src/main/java/com/mlmvpn/core/aether/AetherEngine.kt`    | Process manager + state flows  |
| `android/app/src/main/java/com/mlmvpn/core/aether/AetherStage.kt`     | Stage detection (parity with desktop `aether-manager.js`) |
| `android/app/src/main/java/com/mlmvpn/core/aether/AetherOptions.kt`   | UI options → AETHER_* env vars |
| `android/app/src/main/java/com/mlmvpn/core/aether/AetherScanService.kt` | Foreground service that owns the engine |
| `android/app/src/main/java/com/mlmvpn/scanner/ui/aether/AetherScreen.kt` | Compose UI (3 protocol tabs) |
| `android/app/src/main/jniLibs/<abi>/libaether.so`                     | The compiled Rust binary, packaged as a native library |
| `android/scripts/install-aether-binary.ps1`                           | Fetches a release build and drops it into `jniLibs/` |
| `aether-src/build-android.sh`                                         | Cross-compile to Android targets |

## Building the binary

You don't need to build it for normal development — an existing release APK ships with
the binary pre-built and stored under `jniLibs/<abi>/libaether.so`. The binary ships as
a "native library" on purpose: since Android 10 (API 29), an app targeting API 29+
cannot execute anything it writes into its own data directory, so `nativeLibraryDir`
(populated from `jniLibs/<abi>/lib*.so` by the package installer) is the only workable
location. `AetherEngine.prepareBinary()` just resolves the already-unpacked path in
`nativeLibraryDir` -- nothing is copied or chmod'd at runtime.

To grab a prebuilt release without compiling anything, run
[`scripts/install-aether-binary.ps1`](scripts/install-aether-binary.ps1); its header
comment explains where to download the release archives from.

To build from source (Windows or Linux):

1.  Install the Android NDK from the SDK manager and export `ANDROID_NDK_HOME` to point
    at it.
2.  Install rustup + cargo + the Android targets:

    ```bash
    rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
    ```

3.  Build:

    ```bash
    bash aether-src/build-android.sh
    ```

    The script cross-compiles aether for `arm64-v8a`, `armeabi-v7a` and `x86_64` using
    the NDK's `clang` (Bionic libc), then copies the binaries into
    `android/app/src/main/assets/aether/<abi>/aether`.

## Why a Rust binary?

MASQUE requires HTTP/3 / QUIC. Implementing that on Android in pure Kotlin/Java would
mean rewriting `quiche` (Cloudflare's QUIC library), `boring` (BoringSSL bindings) and
`boringtun` (cross-platform WireGuard). Sharing the existing Rust binary across desktop
and Android keeps the protocol implementation in a single place.

## Lifecycle on the device

```
Package:  APK → jniLibs/<abi>/libaether.so, unpacked by the installer into
                applicationInfo.nativeLibraryDir (already 0755, ABI-correct)
Runtime:  ProcessBuilder("<nativeLibraryDir>/libaether.so")
SOCKS:    127.0.0.1:20810
```

`AetherEngine.prepareBinary()` runs at engine startup and just resolves that path --
there is nothing to copy or chmod, because the package installer already placed an
executable, ABI-correct copy in the one directory an API 29+ app is allowed to exec
from. This replaced an earlier design that shipped the binary as a plain asset and
copied it into `filesDir` at first run, which stopped working once the app's
`targetSdk` reached 29 and the OS began blocking execution from app-private storage.

## Three protocols, one engine

| Protocol    | Env-var           | Notes                                                 |
|-------------|-------------------|-------------------------------------------------------|
| MASQUE      | `AETHER_PROTOCOL=masque` | HTTP/3 (QUIC) by default; falls back to HTTP/2 (TCP) if UDP is throttled |
| WireGuard   | `AETHER_PROTOCOL=wg`     | Classic WARP; AetherNoize hides the handshake pattern |
| WARP-in-WARP| `AETHER_PROTOCOL=gool`   | Nested tunnel; slowest, most resistant to traffic analysis |

MASQUE is the recommended default for most networks with deep packet inspection. WG is
lighter but easier to fingerprint. WARP-in-WARP is the option to reach for when even
WG is being filtered.

## UAE server migration note

The original WireGuard pipeline pointed at a single UAE endpoint
(`194.50.233.133`, UDP 443). That endpoint is no longer reachable from this app, so it
cannot be the single point of failure anymore.

The replacement has three properties the UAE pipeline didn't have:

1.  The engine picks a healthy endpoint *itself*, from the WARP IP pool. No hardcoded
    first hop.
2.  The three protocols above can each be retried independently if the network blocks
    one. A UAE WireGuard ban can't kill MASQUE or WARP-in-WARP.
3.  The endpoint is refreshed whenever the cache says it has stopped working — see
    `cached endpoint .* no longer works` in `AetherStage.kt`.

The `WireguardScanService` foreground service is intentionally left declared in
`AndroidManifest.xml` (so anyone depending on the class won't break) but is no longer
reachable from the bottom-navigation UI; the `enableWireguardTab` preference is
ignored. Future cleanup can remove it entirely once the legacy endpoint is confirmed
unwired in code paths beyond the manifest.

## Verifying it works

After `adb install`

```bash
adb shell am start -n com.mlmvpn.scanner/.scanner.MainActivity
adb logcat -s AetherEngine AetherScanService
```

You should see

```
[AETHER] راه‌اندازی موتور
[AETHER] پروتکل: MASQUE
[AETHER] SOCKS محلی: 127.0.0.1:20810
```

when you press the connect button, then progressing through the steps shown in
`AetherScreen`.

## References

- `aether-manager.js` (the desktop driver this Kotlin layer mirrors) is part of the
  separate desktop project mentioned above, not this repository. It holds the
  authoritative stage rules and option-to-env-var mapping that `AetherStage.kt` and
  `AetherOptions.kt` were ported from.
- That same desktop project's `Public/components/aether.js` was the 1:1 reference for
  the three tabs and the step vocabulary in `AetherScreen.kt`.
