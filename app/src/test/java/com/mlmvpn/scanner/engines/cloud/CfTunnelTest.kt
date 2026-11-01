package com.mlmvpn.scanner.engines.cloud

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * «Deploy and take configs DIRECT; only when Cloudflare is blocked, through one of the app's tunnels.»
 * What decides the order of routes, and what is bounded so a blocked line does not wait a minute.
 */
class CfTunnelTest {

    private val tunnel = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 20830))

    @After fun unbind() { CfTunnel.bindForTest { null } }

    private fun routes(selector: CfTunnelSelector, url: String) = selector.select(URI(url))

    @Test fun a_cloudflare_name_goes_direct_first_then_through_the_tunnel() {
        val s = CfTunnelSelector(tunnel = { tunnel })
        for (url in listOf(
            "https://api.cloudflare.com/client/v4/accounts/x/workers/scripts/y",
            "https://dash.cloudflare.com/",
            "https://app-core-a3f2b1-gw.mysub.workers.dev/",
            "https://some-site.pages.dev/x",
        )) {
            assertEquals(url, listOf(Proxy.NO_PROXY, tunnel), routes(s, url))
        }
    }

    @Test fun nothing_changes_when_no_tunnel_is_on() {
        val s = CfTunnelSelector(tunnel = { null })
        assertEquals(listOf(Proxy.NO_PROXY), routes(s, "https://api.cloudflare.com/x"))
        assertEquals(listOf(Proxy.NO_PROXY), routes(s, "https://a.workers.dev/"))
    }

    @Test fun any_other_name_is_never_proxied_by_this_selector() {
        val s = CfTunnelSelector(tunnel = { tunnel })
        for (url in listOf("https://github.com/x", "https://1.1.1.1/dns-query", "https://workers.dev.evil.example/", "https://notcloudflare.com/", "https://api.cloudflare.com.evil.example/")) {
            assertEquals(url, listOf(Proxy.NO_PROXY), routes(s, url))
        }
        assertEquals(listOf(Proxy.NO_PROXY), s.select(null))
    }

    @Test fun the_tunnel_is_asked_afresh_every_time_so_turning_one_on_takes_effect_at_once() {
        var on: Proxy? = null
        val s = CfTunnelSelector(tunnel = { on })
        assertEquals(listOf(Proxy.NO_PROXY), routes(s, "https://api.cloudflare.com/x"))
        on = tunnel
        assertEquals(listOf(Proxy.NO_PROXY, tunnel), routes(s, "https://api.cloudflare.com/x"))
        on = null
        assertEquals(listOf(Proxy.NO_PROXY), routes(s, "https://api.cloudflare.com/x"))
    }

    @Test fun a_failure_report_changes_nothing_okhttp_remembers_failed_routes_itself() {
        val s = CfTunnelSelector(tunnel = { tunnel })
        s.connectFailed(URI("https://api.cloudflare.com/x"), InetSocketAddress("127.0.0.1", 20830), java.io.IOException("refused"))
        assertEquals(listOf(Proxy.NO_PROXY, tunnel), routes(s, "https://api.cloudflare.com/x"))
    }

    @Test fun while_a_tunnel_is_on_direct_gives_up_sooner_and_tries_fewer_addresses() {
        assertEquals("without a tunnel nothing is shortened", 15_000, CfTunnel.directConnectMs(15_000))
        assertEquals(listOf(1, 2, 3, 4), CfTunnel.capDirect(listOf(1, 2, 3, 4)))
        assertNull(CfTunnel.proxy())

        CfTunnel.bindForTest { tunnel }
        assertTrue(CfTunnel.available())
        assertEquals(CfTunnel.DIRECT_CONNECT_MS, CfTunnel.directConnectMs(15_000))
        assertEquals("a short timeout stays short", 2_000, CfTunnel.directConnectMs(2_000))
        assertEquals("zero means no timeout and is left alone", 0, CfTunnel.directConnectMs(0))
        assertEquals(listOf(1, 2), CfTunnel.capDirect(listOf(1, 2, 3, 4)))
        assertEquals(listOf(1), CfTunnel.capDirect(listOf(1)))
    }

    @Test fun the_advice_says_what_to_do_in_both_languages_and_changes_when_a_tunnel_was_already_on() {
        val (faOff, enOff) = CfTunnel.advice(tunnelWasOn = false)
        assertTrue(faOff, faOff.contains("یکی از تونل‌های برنامه") && faOff.contains("روشن کنید"))
        assertTrue(enOff, enOff.contains("Turn on one of the app's tunnels"))
        val (faOn, enOn) = CfTunnel.advice(tunnelWasOn = true)
        assertTrue(faOn, faOn.contains("نه مستقیم باز شد و نه از تونل روشن"))
        assertTrue(enOn, enOn.contains("through the tunnel that is on"))
    }
}
