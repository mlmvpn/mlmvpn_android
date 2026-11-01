package com.mlmvpn.scanner.engines.windscribe

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WindscribeContractTest {
    @Test fun `Stealth and WebSocket use official distinct endpoint addresses`() {
        val server = WsContract.servers(LOCATIONS, NODES, false, emptySet()).single()
        assertEquals("https://192.0.2.3:443", WsContract.proxy(server, WsMethod("1", WsProtocol.STEALTH, 443)).remote)
        assertEquals("wss://192.0.2.1:443/tcp/127.0.0.1/1194", WsContract.proxy(server, WsMethod("1", WsProtocol.WSTUNNEL, 443)).remote)
        val config = WsContract.openVpn("client\nproto udp\n<ca>\nCA\n</ca>", server, WsMethod("1", WsProtocol.STEALTH, 443), "user", "pass")
        assertTrue(config.contains("proto tcp-client"))
        assertTrue(config.contains("verify-x509-name dc-cert name"))
    }
    @Test fun `cached reconnect tolerates only transport failure and never authentication failure`() {
        assertTrue(WsSearchPolicy.mayUseCache(WsError.NETWORK, true))
        assertFalse(WsSearchPolicy.mayUseCache(WsError.NETWORK, false))
        listOf(WsError.SESSION_EXPIRED, WsError.AUTH, WsError.QUOTA, WsError.ACCOUNT_UNAVAILABLE, WsError.STORAGE).forEach {
            assertFalse(WsSearchPolicy.mayUseCache(it, true))
        }
    }
    @Test fun `quick discovery has a bounded total budget and reaches TLS transport early`() {
        assertTrue(WsSearchPolicy.CONNECT_BUDGET_MS <= 180_000)
        val server = WsContract.servers(LOCATIONS, NODES, false, emptySet()).single()
        val plan = WsPlanner.plan(listOf(server), WsProtocol.values().associateWith { listOf(443) }, null, null, cf = true)
        assertEquals(WsProtocol.WIREGUARD, plan.first().protocol)
        assertTrue(plan.take(6).any { it.protocol == WsProtocol.STEALTH && it.cf })
        assertTrue(plan.take(6).any { it.protocol == WsProtocol.WSTUNNEL && it.cf })
        assertTrue(plan.take(6).any { it.protocol == WsProtocol.OPENVPN_UDP && it.cf })
    }
    @Test fun `a blackholed server is only reached through Cloudflare`() {
        val server = WsContract.servers(LOCATIONS, NODES, false, emptySet()).single()
        val ports = WsProtocol.values().associateWith { listOf(443, 80) }
        val plan = WsPlanner.plan(listOf(server), ports, null, null, cf = true) { _, _ -> false }
        assertTrue(plan.filter { it.protocol != WsProtocol.WIREGUARD }.all { it.cf })
        val open = WsPlanner.plan(listOf(server), ports, WsProtocol.OPENVPN_TCP, null, cf = false) { _, _ -> true }
        assertTrue(open.isNotEmpty() && open.none { it.cf })
        assertTrue(open.any { it.padding })
    }
    @Test fun `test all covers every server with every advertised protocol once`() {
        val base = WsContract.servers(LOCATIONS, NODES, false, emptySet()).single()
        val servers = listOf(base.copy(id = "us", dcId = "u", country = "US"), base.copy(id = "de", dcId = "d", country = "DE"))
        val plan = WsPlanner.matrix(servers, WsProtocol.values().associateWith { listOf(80, 443) })
        assertEquals(10, plan.size)
        assertEquals(listOf("de", "us"), plan.map { it.serverId }.distinct())
        assertTrue(plan.all { it.port == 443 })
        assertTrue(plan.filter { it.protocol != WsProtocol.WIREGUARD }.all { it.cf })
        assertEquals(plan.size, plan.distinct().size)
    }
    @Test fun `a steadier route outranks a faster unsteady one`() {
        val steady = WsMeasurement(WsMethod("1", WsProtocol.OPENVPN_TCP, 443), 0, 300, 2_000_000, 5, 5, 0)
        val shaky = WsMeasurement(WsMethod("2", WsProtocol.STEALTH, 443), 0, 100, 9_000_000, 3, 5, 0)
        assertTrue(WsPlanner.SCORE.compare(steady, shaky) > 0)
        assertEquals(steady, WsPlanner.best(listOf(shaky, steady)))
    }
    @Test fun `ranking a row without a speed next to one with a speed does not crash`() {
        // Regression (2026-10-10): `downloadBps ?: 0` compared an Int with a Long and crashed the screen.
        val noSpeed = WsMeasurement(WsMethod("1", WsProtocol.OPENVPN_TCP, 443), 0, 200, null, 5, 5, 0)
        val speed = WsMeasurement(WsMethod("2", WsProtocol.STEALTH, 443), 0, 200, 4_000_000, 5, 5, 0)
        assertEquals(speed, listOf(noSpeed, speed).maxWithOrNull(WsPlanner.SCORE))
        assertEquals(speed, WsPlanner.best(listOf(noSpeed, speed)))
    }
    @Test fun `the method that last worked goes first even when the order would not make it`() {
        val base = WsContract.servers(LOCATIONS, NODES, false, emptySet()).single()
        val servers = listOf(base.copy(id = "a", dcId = "a", country = "DE"), base.copy(id = "b", dcId = "b", country = "US"))
        val remembered = WsMethod("b", WsProtocol.OPENVPN_UDP, 443, cf = true)
        val plan = WsPlanner.plan(servers, WsProtocol.values().associateWith { listOf(443) }, null, remembered, cf = true) { _, _ -> false }
        assertEquals(remembered, plan.first())
        assertEquals(1, plan.count { it == remembered })
    }
    @Test fun `servers near Iran are tried before far ones`() {
        val base = WsContract.servers(LOCATIONS, NODES, false, emptySet()).single()
        val us = base.copy(id = "us", country = "US", load = 1)
        val de = base.copy(id = "de", country = "DE", load = 40)
        assertEquals(listOf("de", "us"), listOf(us, de).sortedWith(WsPlanner.ORDER).map { it.id })
    }
    @Test fun `wireguard config carries the account key and the node endpoint`() {
        val server = WsContract.servers(LOCATIONS, NODES, false, emptySet()).single()
        val conf = WsContract.wireGuard("PRIV=", "100.64.0.9/32", JSONObject("""{"PresharedKey":"PSK="}"""), server, 443)
        assertTrue(conf.contains("PrivateKey = PRIV=\n"))
        assertTrue(conf.contains("PresharedKey = PSK=\n"))
        assertTrue(conf.contains("Endpoint = 192.0.2.3:443\n"))
        assertTrue(conf.contains("AllowedIPs = 0.0.0.0/0"))
    }
    @Test fun `cloudflare relay keeps OpenVPN on the loopback and Stealth proxy on the relay`() {
        val server = WsContract.servers(LOCATIONS, NODES, false, emptySet()).single()
        val tcp = WsContract.openVpn("client\n<ca>\nCA\n</ca>", server, WsMethod("1", WsProtocol.OPENVPN_TCP, 443, cf = true), "u", "p", 40000)
        assertTrue(tcp.contains("remote 127.0.0.1 40000 tcp-client"))
        assertTrue(tcp.contains("verify-x509-name dc-cert name"))
        assertEquals("https://127.0.0.1:40001", WsContract.proxy(server, WsMethod("1", WsProtocol.STEALTH, 443, cf = true), 40001).remote)
    }
    @Test fun `route exclusion leaves out exactly the carrier edge`() {
        com.mlmvpn.scanner.openvpn.OpenVpnBypass.add("203.0.113.7")
        try {
            val routes = com.mlmvpn.scanner.openvpn.OpenVpnBypass.split("0.0.0.0", 0)
            assertEquals(32, routes.size)
            fun covered(ip: String) = routes.any { (a, p) ->
                val x = java.net.InetAddress.getByName(ip).address; val n = java.net.InetAddress.getByName(a).address
                (0 until p).all { i -> ((x[i / 8].toInt() shr (7 - i % 8)) and 1) == ((n[i / 8].toInt() shr (7 - i % 8)) and 1) }
            }
            assertFalse(covered("203.0.113.7"))
            assertTrue(covered("203.0.113.6") && covered("1.1.1.1") && covered("255.255.255.255"))
            assertEquals(listOf("::" to 0), com.mlmvpn.scanner.openvpn.OpenVpnBypass.split("::", 0))
        } finally { com.mlmvpn.scanner.openvpn.OpenVpnBypass.remove("203.0.113.7") }
    }
    @Test fun `quota comes from server and missing quota stays unknown`() {
        val account = WsContract.account(JSONObject("""{"user_id":"42","username":"alice","email_status":"1","is_premium":0,"status":1,"traffic_max":"10000","traffic_used":"2600","last_reset":"2026-10-01"}"""), 1234)
        assertEquals(7400L, account.remaining)
        assertTrue(account.verified)
        assertNull(WsContract.account(JSONObject("""{"user_id":"43","username":"bob"}"""), 1234).remaining)
    }
    @Test fun `v2 inventory joins datacenter and hides paid disabled and revoked servers`() {
        val servers = WsContract.servers(LOCATIONS, NODES, false, emptySet())
        assertEquals(listOf("1"), servers.map { it.id })
        assertEquals("Frankfurt", servers.single().city)
        assertEquals("192.0.2.2", servers.single().ip2)
        assertEquals("DE", servers.single().country)
    }
    @Test fun `allowed paid locations require account entitlement`() {
        assertEquals(setOf("1", "2"), WsContract.servers(LOCATIONS, NODES, false, setOf("20")).map { it.id }.toSet())
    }
    @Test fun `protocol plan uses advertised ports only and reuses proven method`() {
        val server = WsContract.servers(LOCATIONS, NODES, false, emptySet()).single()
        val ports = mapOf(WsProtocol.WIREGUARD to listOf(443, 53), WsProtocol.OPENVPN_TCP to listOf(443))
        val remembered = WsMethod("1", WsProtocol.OPENVPN_TCP, 443, true)
        val plan = WsPlanner.plan(listOf(server), ports, null, remembered)
        assertEquals(remembered, plan.first())
        assertFalse(plan.any { it.protocol == WsProtocol.OPENVPN_UDP })
        // WireGuard once, on the HTTPS-looking port: the Amnezia engine falls back across nodes itself.
        assertEquals(listOf(443), plan.filter { it.protocol == WsProtocol.WIREGUARD }.map { it.port })
        assertTrue(plan.all { ports[it.protocol].orEmpty().contains(it.port) })
        assertEquals(plan.size, plan.distinct().size)
    }
    @Test fun `best connection is selected from successful measurements`() {
        val slow = WsMeasurement(WsMethod("1", WsProtocol.WIREGUARD, 443), 2000, 80, 1_000_000, 3, 3, 1234)
        val failed = WsMeasurement(WsMethod("2", WsProtocol.WIREGUARD, 443), 0, null, 100_000_000, 0, 3, 1234)
        val fast = WsMeasurement(WsMethod("3", WsProtocol.OPENVPN_TCP, 443), 2500, 90, 8_000_000, 3, 3, 1234)
        assertEquals(fast, WsPlanner.best(listOf(slow, failed, fast)))
        assertNull(WsPlanner.best(listOf(failed)))
    }
    @Test fun `wireguard address agrees with official public key hashing`() {
        assertEquals("100.104.122.173/32", WsContract.address("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "100.64.0.0/10"))
    }
    @Test fun `API errors never expose server messages or session secrets`() {
        val failure = runCatching { WsContract.data("""{"errorCode":701,"errorMessage":"SECRET_TOKEN"}""") }.exceptionOrNull()
        assertTrue(failure is WsException)
        assertEquals(WsError.SESSION_EXPIRED, (failure as WsException).error)
        assertFalse(failure.toString().contains("SECRET_TOKEN"))
    }
    @Test fun `OpenVPN template retains certificates and replaces endpoint with selected protocol`() {
        val server = WsContract.servers(LOCATIONS, NODES, false, emptySet()).single()
        val config = WsContract.openVpn("client\nremote OLD 1194\nproto udp\n<ca>\nCA\n</ca>\n", server, WsMethod("1", WsProtocol.OPENVPN_TCP, 443), "user", "pass")
        assertTrue(config.contains("remote 192.0.2.2 443 tcp-client"))
        assertTrue(config.contains("<ca>\nCA\n</ca>"))
        assertTrue(config.contains("verify-x509-name dc-cert name"))
        assertFalse(config.contains("OLD"))
    }
    @Test fun `TCP fragmentation routes provider profile through loopback without changing server port`() {
        val profile = com.mlmvpn.scanner.openvpn.Profile("windscribe-1", "Windscribe", "client\nproto tcp-client\nremote 192.0.2.2 443 tcp-client\nverify-x509-name dc-cert name\n", listOf(com.mlmvpn.scanner.openvpn.Remote("192.0.2.2", 443, "tcp-client")))
        val effective = com.mlmvpn.scanner.openvpn.ProfileRuntime.effective(profile, false, 20000)
        assertTrue(effective.contains("remote 127.0.0.1 20000 tcp-client"))
        assertTrue(effective.contains("verify-x509-name dc-cert name"))
        assertFalse(effective.contains("7011"))
    }
    @Test fun `bounded search reaches TCP before exhausting WireGuard servers`() {
        val server = WsContract.servers(LOCATIONS, NODES, false, emptySet()).single()
        val nodes = (1..4).map { server.copy(id = "$it", dcId = "$it") }
        val plan = WsPlanner.plan(nodes, mapOf(WsProtocol.WIREGUARD to listOf(443, 53), WsProtocol.OPENVPN_TCP to listOf(443)), null, null)
        assertTrue(plan.take(4).any { it.protocol == WsProtocol.OPENVPN_TCP })
    }
    @Test fun `partial tunnel verification cannot beat a consistently working connection`() {
        val reliable = WsMeasurement(WsMethod("1", WsProtocol.WIREGUARD, 443), 2000, 80, null, 3, 3, 1234)
        val partial = WsMeasurement(WsMethod("2", WsProtocol.OPENVPN_TCP, 443), 1000, 20, 100_000_000, 1, 3, 1234)
        assertEquals(reliable, WsPlanner.best(listOf(reliable, partial)))
    }
    companion object {
        val LOCATIONS = """{"locations":[{"id":10,"country_code":"DE","name":"Germany","premium_only":0,"datacenters":[{"id":11,"city":"Frankfurt","status":1,"premium":0,"wg_pubkey":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","wg_endpoint":"wg.example","ovpn_x509":"dc-cert"},{"id":12,"city":"Berlin","status":0,"premium":0}]},{"id":20,"country_code":"US","name":"US","premium_only":1,"datacenters":[{"id":21,"city":"NY","status":1,"premium":1}]}]}"""
        val NODES = """{"revision":1,"servers":[{"id":1,"dc_id":11,"host":"node.example","ip":"192.0.2.1","ip2":"192.0.2.2","ip3":"192.0.2.3","net_load":25,"weight":100,"fd":0},{"id":2,"dc_id":21,"host":"paid.example","ip":"192.0.2.4","fd":0},{"id":3,"dc_id":12,"host":"disabled.example","ip":"192.0.2.5"},{"id":4,"dc_id":11,"host":"revoked.example","ip":"192.0.2.6","fd":1}]}"""
    }
}
