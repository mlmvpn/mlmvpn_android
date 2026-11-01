# Official Windscribe proxy binary

`libwsproxy.so` is the unmodified `libwg-go.so` from the official Android
[v4.3 release](https://github.com/Windscribe/Android-App/releases/tag/v4.3),
renamed to avoid replacing MLMVPN's existing AmneziaWG binary.

Source: https://github.com/Windscribe/Android-App/tree/v4.3/wgtunnel/tools/libwg-go
and https://github.com/Windscribe/wstunnel/tree/v1.0.7 .
The upstream GPL license is retained in this directory.

Official `mobile.apk` URL:
https://github.com/Windscribe/Android-App/releases/download/v4.3/mobile.apk

GitHub asset SHA-256 (verified before extraction):
`2f763a5f9af3cbdcd551a72bf34d373ce7f71a99a796856b863f95ad39ed76cc`.

Extracted ARM64 SHA-256:
`2f483432104d15cde09069e93785ca0d786bd2edf651ca083c867264aa750934`.

Extracted ARM32 SHA-256:
`b01edfbac43ae568b7c3bc806bc01d1f55e5ad0ab6bf4fe804645b2207686b6f`.

The four `WSTunnelLib` JNI symbols were inspected before integration. The library
loads only in the private `:wsproxy` process, preserving the upstream JNI class
name. It receives only the public endpoint, transport type, padding and SNI, never
account sessions/credentials. OpenVPN inside the transport verifies the provider's
certificate identity. The client uses the official TLS/WebSocket framing and
duplicates the remote socket through Binder so the VPN service can protect it.
