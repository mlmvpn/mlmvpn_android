package com.mlmvpn.scanner.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {
    @Test fun streamedPemNeverLeaksBodyLines() {
        val stream = SecretRedactor.Stream()
        assertFalse(stream.redact("-----BEGIN PRIVATE KEY-----").contains("BEGIN"))
        assertFalse(stream.redact("secret-body-on-a-separate-line").contains("secret-body"))
        assertFalse(stream.redact("-----END PRIVATE KEY-----").contains("END"))
        assertEquals("connected", stream.redact("connected"))
    }
    private fun clean(s: String) = SecretRedactor.redact(s)

    @Test fun `global api key is masked`() {
        val key = "0123456789abcdef0123456789abcdef01234"
        assertFalse(clean("key was $key here").contains(key))
    }

    @Test fun `bearer token is masked`() {
        val out = clean("Authorization: Bearer AbC123_def-456.ghi")
        assertFalse(out.contains("AbC123"))
        assertTrue(out.contains("Bearer"))
    }

    @Test fun `forty char api token is masked`() {
        val tok = "aB3dE5gH7jK9mN1pQ3sT5vW7yZ9bC1dE3fG5hJ7k"
        assertEquals(40, tok.length)
        assertFalse(clean("token $tok failed").contains(tok))
    }

    @Test fun `auth headers and emails are masked`() {
        val out = clean("X-Auth-Email: someone@example.com X-Auth-Key: deadbeef")
        assertFalse(out.contains("someone@example.com"))
        assertFalse(out.contains("deadbeef"))
    }

    @Test fun `secret named query parameters are masked`() {
        val out = clean("GET https://x.workers.dev/api?password=hunter22&mode=1")
        assertFalse(out.contains("hunter22"))
        assertTrue(out.contains("mode=1"))
    }

    @Test fun `ordinary stack frames pass through`() {
        val frame = "at com.mlmvpn.scanner.data.CloudManager.loadAccounts(CloudManager.kt:63)"
        assertEquals(frame, clean(frame))
    }

    @Test fun `wireguard private key is masked`() {
        val wg = "cGFzc3dvcmQxMjM0NTY3ODkwYWJjZGVmZ2hpams9"  // 40-char, under the token rule
        val real = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNk="   // 44-char base64, 32 bytes
        val out = clean("wg_private_key=$real and interface $wg")
        assertFalse(out.contains(real))
    }

    @Test fun `pem private material is masked`() {
        val pem = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqh\nkiG9w0BAQEF\n-----END PRIVATE KEY-----"
        val out = clean("cert_pem: $pem\nnext line")
        assertFalse(out.contains("MIIEvQIBADANBgkqh"))
        assertTrue(out.contains("next line"))
    }

    @Test fun `identity named fields are masked`() {
        assertFalse(clean("""{"private_key":"abcd1234efgh","ipv4":"1.2.3.4"}""").contains("abcd1234efgh"))
        assertFalse(clean("access_token = ZZZtopsecretvalueZZZ").contains("ZZZtopsecretvalueZZZ"))
    }

    @Test fun `uuid device ids are masked`() {
        val uuid = "11111111-2222-3333-4444-555555555555"
        assertFalse(clean("device_id $uuid").contains(uuid))
    }
}
