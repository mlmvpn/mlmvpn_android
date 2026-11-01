# Windscribe networking library

`app/libs/wsnet.aar` is the unmodified binary shipped in the official
[Windscribe Android source](https://github.com/Windscribe/Android-App/tree/main/base/src/main/libs),
retrieved on 2026-10-10. It contains the official WSNet/Scapix JNI bindings and
`libwsnet.so` for Android. MLMVPN packages only its existing ARM ABI filters.

Research snapshots: Android-App `2c585dd5979ba93655853d56d849ed8ad2c964cb`,
wsnet `c63bf287d79559bb8a6a8d5e32f843250c748309`.

SHA-256: `5b0145d526aa10f6b7ec094639bfc8dc2f187d76d1b70915cf4da4802b822f11`.

The corresponding networking source and build instructions are at
[Windscribe/wsnet](https://github.com/Windscribe/wsnet). The GPL-2.0 license is
included as `LICENSE` in this directory. Android authentication and protocol
contracts were checked against the official Android client, including its
`ApiCallManager`, `WgConfigRepository`, `WireguardUtil`, `LocationResponse`,
`ServerResponse`, `VPNProfileCreator`, and `VpnProfile` implementation.

The supplied tile SVG was converted to an Android vector while retaining its
logo path. Source artwork: `windscribe-v2-svgrepo-com.svg`, supplied by the user,
with attribution in its source to SVG Repo and krisu's monochrome Windscribe mark.
