package com.mlmvpn.core.warp

import android.util.Base64

object WarpCrypto {

    data class KeyPair(val privateKeyBase64: String, val publicKeyBase64: String)

    /**
     * Generates a WireGuard compatible Curve25519 KeyPair using TweetNaCl (X25519).
     * The private key is generated securely and left unclamped as WireGuard expects.
     */
    fun generateKeyPair(): KeyPair {
        // TweetNaclFast.Box.keyPair() generates a 32-byte random secret key, 
        // and its corresponding X25519 public key.
        val kp = TweetNaclFast.Box.keyPair()
        
        // Base64 encode without newlines (NO_WRAP)
        val privKey = Base64.encodeToString(kp.secretKey, Base64.NO_WRAP)
        val pubKey = Base64.encodeToString(kp.publicKey, Base64.NO_WRAP)
        
        return KeyPair(privKey, pubKey)
    }
}
