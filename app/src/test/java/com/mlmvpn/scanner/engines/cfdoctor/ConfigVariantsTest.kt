package com.mlmvpn.scanner.engines.cfdoctor

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConfigVariantsTest {
    private val base="""{"outbounds":[{"tag":"proxy","streamSettings":{"security":"tls","tlsSettings":{"serverName":"private.example","fingerprint":"chrome"}}}]}"""
    @Test fun fragmentationIsAttachedToTheProxyStreamWithoutChangingCredentials() {
        val j=JSONObject(ConfigVariants.apply(base,"C3.F15"))
        val s=j.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertEquals("private.example",s.getJSONObject("tlsSettings").getString("serverName"))
        assertEquals(2,s.getJSONObject("finalmask").getJSONArray("tcp").length())
        assertFalse(base.contains("finalmask"))
    }
    @Test fun echUsesOnlyApprovedResolvers() {
        assertThrows(IllegalArgumentException::class.java) { ConfigVariants.apply(base,"C9.https://evil.example") }
        val j=JSONObject(ConfigVariants.apply(base,"C9.google"))
        val tls=j.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings").getJSONObject("tlsSettings")
        assertEquals("full",tls.getString("echForceQuery"))
    }
}
