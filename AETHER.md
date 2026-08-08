# Aether on Android

This document explains how the Aether censorship-circumvention engine (MASQUE /
WireGuard / WARP-in-WARP) is bundled and run in the Android app. The same Rust binary
that drives the desktop `core/aether.exe` is shipped inside the APK and spawned by the
Kotlin layer at runtime.

## What lives where

| File                                                                  | Role                           |
|-----------------------------------------------------------------------|--------------------------------|
| `core/aether/aether/src/main.rs` & friends                           | The Rust binary source         |
| `android/app/src/main/java/com/mlmvpn/core/aether/AetherEngine.kt`    | Process manager + state flows  |
| `android/app/src/main/java/com/mlmvpn/core/aether/AetherStage.kt`     | Stage detection (parity with desktop `aether-manager.js`) |
| `android/app/src/main/java/com/mlmvpn/core/aether/AetherOptions.kt`   | UI options → AETHER_* env vars |
| `android/app/src/main/java/com/mlmvpn/core/aether/AetherScanService.kt` | Foreground service that owns the engine |
| `android/app/src/main/java/com/mlmvpn/scanner/ui/aether/AetherScreen.kt` | Compose UI (3 protocol tabs) |
| `android/app/src/main/assets/aether/<abi>/aether`                     | The compiled Rust binary         |
| `aether-src/build-android.sh`                                         | Cross-compile to Android targets |

## Building the binary

You don't need to build it for normal development — an existing release APK ships with
the binary pre-built and stored under `assets/aether/`.

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
Asset:    APK → assets/aether/<abi>/aether (read-only)
First:    filesDir/aether/<abi>/aether (0755, copied + chmod once)
Runtime:  ProcessBuilder("filesDir/aether/<abi>/aether")
SOCKS:    127.0.0.1:20810
```

`AetherEngine.prepareBinary()` runs at engine startup; it copies the asset once and
chmods it. Subsequent runs hit the cached file.

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

- aether-manager.js (the desktop driver this Kotlin layer mirrors) lives at the repo
  root. Read it for the authoritative stage rules and option-to-env-var mapping.
- Public/components/aether.js (desktop UI) was the 1:1 reference for the three tabs
  and the step vocabulary in `AetherScreen`.
