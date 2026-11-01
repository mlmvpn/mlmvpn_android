package com.mlmvpn.core.aether

/**
 * The ClientHello of Aether's own TLS: MASQUE over HTTP/2, the calls to the WARP API and the DoH
 * lookup of the ECH key. Aether 2.4.0 takes each part of it as an option (`--tls-ciphers`,
 * `--tls-groups`, `--disable-grease`, ...; the env names below are what its cli.rs sets for them).
 *
 * [CHROME] is the core's own and sets nothing. [SEMI_PYTHON] is the handshake PattNG ships for
 * Irancell-type filters that pass Python's ClientHello and drop Chrome's: Python's TLS 1.2 suites
 * in Python's order (c00a/c014 in place of the CBC-SHA384 pair BoringSSL lacks), its TLS 1.3
 * order, its groups and signature algorithms, no GREASE, no OCSP or SCT request, one fixed order
 * of extensions. On the home line of 2026-10-26 the core took it, registered a MASQUE key with it
 * in 7 s and carried MASQUE over HTTP/2 with it (plus --fragment) in 12 s.
 */
enum class AetherTls(val key: String, val label: String, val env: Map<String, String>) {
    CHROME("chrome", "Chrome", emptyMap()),
    SEMI_PYTHON(
        "semi-python", "semi-python",
        mapOf(
            "AETHER_TLS_CIPHERS" to "ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:" +
                "ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:" +
                "ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:" +
                "ECDHE-ECDSA-AES256-SHA:ECDHE-RSA-AES256-SHA:ECDHE-ECDSA-AES128-SHA256:ECDHE-RSA-AES128-SHA256",
            "AETHER_TLS13_CIPHERS" to "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_128_GCM_SHA256",
            "AETHER_TLS_GROUPS" to "X25519MLKEM768:X25519:P-256:P-384:P-521",
            "AETHER_TLS_SIGALGS" to "mldsa65:mldsa87:mldsa44:ecdsa_secp256r1_sha256:ecdsa_secp384r1_sha384:" +
                "ecdsa_secp521r1_sha512:ed25519:rsa_pss_rsae_sha256:rsa_pss_rsae_sha384:rsa_pss_rsae_sha512:" +
                "rsa_pkcs1_sha256:rsa_pkcs1_sha384:rsa_pkcs1_sha512",
            "AETHER_DISABLE_GREASE" to "1",
            "AETHER_DISABLE_OCSP_STAPLING" to "1",
            "AETHER_DISABLE_SCT" to "1",
            "AETHER_DISABLE_EXTENSION_PERMUTATION" to "1",
        ),
    );

    companion object {
        fun byKey(key: String?): AetherTls = values().firstOrNull { it.key == key } ?: CHROME
    }
}
