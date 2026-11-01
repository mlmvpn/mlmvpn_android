package com.mlmvpn.scanner.engines.flux

import com.mlmvpn.scanner.engines.flux.FluxFixtures.cfWs
import com.mlmvpn.scanner.engines.flux.FluxFixtures.hy2
import com.mlmvpn.scanner.engines.flux.FluxFixtures.node
import com.mlmvpn.scanner.engines.flux.FluxFixtures.reality
import com.mlmvpn.scanner.engines.flux.core.memory.FluxMemory
import com.mlmvpn.scanner.engines.flux.core.memory.FluxState
import com.mlmvpn.scanner.engines.flux.core.model.FailReason
import com.mlmvpn.scanner.engines.flux.core.model.Family
import com.mlmvpn.scanner.engines.flux.core.model.FluxEgressIdentity
import com.mlmvpn.scanner.engines.flux.core.model.IpMode
import com.mlmvpn.scanner.engines.flux.core.model.NetVerdict
import com.mlmvpn.scanner.engines.flux.core.model.Tri
import com.mlmvpn.scanner.engines.flux.core.net.Cloudflare
import com.mlmvpn.scanner.engines.flux.core.plan.FluxPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FluxPlannerTest {

    private val now = 1_000_000_000L
    private val cf = node(cfWs())
    private val rl = node(reality(ip = "169.40.42.235"))
    private val rl6 = node(reality(ip = "2a01:4f8::1"))
    private val hy = node(hy2())
    private val edges = { f: Family, n: Int -> (1..n).map { if (f == Family.V4) "104.16.0.$it" else "2606:4700::$it" } }

    private fun inp(
        nodes: List<com.mlmvpn.scanner.engines.flux.core.model.FluxNode> = listOf(cf, rl, hy),
        verdict: NetVerdict = NetVerdict(),
        mode: IpMode = IpMode.V4,
        state: FluxState = FluxState(),
        country: String? = null,
        wave: Int = 1,
    ) = FluxPlanner.Input(nodes, state, "netA", now, country, mode, verdict, sampleEdges = edges, wave = wave)

    @Test fun `cloudflare reachable - cdn node expanded over edges`() {
        val cs = FluxPlanner.candidates(inp())
        val cfCands = cs.filter { it.node.id == cf.id }
        assertTrue(cfCands.size >= 3)
        assertTrue(cfCands.all { Cloudflare.isCloudflare(it.dialAddress) })
        // The origin names stay the node's own.
        assertTrue(cfCands.all { it.node.host == "edge-a.pages.dev" })
    }

    @Test fun `cloudflare cut - no cloudflare candidate at all, direct ones remain`() {
        val cut = NetVerdict(cfV4 = Tri.NO, cfV6 = Tri.NO, at = now)
        val cs = FluxPlanner.candidates(inp(verdict = cut))
        assertTrue(cs.none { it.node.id == cf.id })
        assertTrue(cs.any { it.node.id == rl.id })
        assertTrue(cs.any { it.node.id == hy.id })
    }

    @Test fun `udp blocked - no hysteria2`() {
        val cs = FluxPlanner.candidates(inp(verdict = NetVerdict(udp = Tri.NO)))
        assertTrue(cs.none { it.node.id == hy.id })
    }

    @Test fun `ipv6 mode on a network without ipv6 has nothing to race`() {
        assertTrue(FluxPlanner.candidates(inp(mode = IpMode.V6, verdict = NetVerdict(v6 = Tri.NO))).isEmpty())
    }

    @Test fun `ipv4 mode never dials ipv6, ipv6 mode never dials ipv4`() {
        val v4 = FluxPlanner.candidates(inp(nodes = listOf(rl, rl6, cf), mode = IpMode.V4))
        assertTrue(v4.all { it.family == Family.V4 && Family.ofLiteral(it.dialAddress) == Family.V4 })
        val v6 = FluxPlanner.candidates(inp(nodes = listOf(rl, rl6, cf), mode = IpMode.V6))
        assertTrue(v6.isNotEmpty())
        assertTrue(v6.all { it.family == Family.V6 && Family.ofLiteral(it.dialAddress) == Family.V6 })
    }

    @Test fun `proven wrong country is never raced for that country`() {
        val s = FluxState(egress = mapOf(
            com.mlmvpn.scanner.engines.flux.core.model.FluxCandidate.egressKey(rl.id, null) to FluxEgressIdentity(countryCode = "DE", verifiedAt = now, confidence = 1.0),
        ))
        val cs = FluxPlanner.candidates(inp(nodes = listOf(rl), state = s, country = "US"))
        assertTrue(cs.isEmpty())
        assertEquals(1, FluxPlanner.candidates(inp(nodes = listOf(rl), state = s, country = "DE")).count { it.fragment == com.mlmvpn.scanner.engines.flux.core.model.FragmentProfile.OFF })
    }

    @Test fun `circuit breaker keeps a failing route out`() {
        val c = FluxPlanner.candidates(inp(nodes = listOf(rl))).single { it.fragment == com.mlmvpn.scanner.engines.flux.core.model.FragmentProfile.OFF }
        var s = FluxState()
        repeat(3) { s = FluxMemory.recordFailure(s, "netA", c.id, now, FailReason.TCP_TIMEOUT) }
        assertTrue(FluxPlanner.candidates(inp(nodes = listOf(rl), state = s)).none { it.id == c.id })
        // On another network the same route is untouched.
        assertTrue(FluxPlanner.candidates(inp(nodes = listOf(rl), state = s).copy(net = "netB")).any { it.id == c.id })
    }

    @Test fun `warm start returns the known route without racing, and skips a dead primary`() {
        val all = FluxPlanner.candidates(inp(nodes = listOf(rl, node(reality(ip = "1.2.3.4"))))).filter { it.fragment == com.mlmvpn.scanner.engines.flux.core.model.FragmentProfile.OFF }
        val (p, sb) = all
        var s = FluxState()
        s = FluxMemory.recordSuccess(s, "netA", p.id, now, 40)
        s = FluxMemory.recordSuccess(s, "netA", sb.id, now, 60)
        s = FluxMemory.setBest(s, "netA", null, IpMode.V4, listOf(p.id, sb.id))
        val nodes = listOf(rl, node(reality(ip = "1.2.3.4")))
        assertEquals(listOf(p.id, sb.id), FluxPlanner.warm(inp(nodes = nodes, state = s)).map { it.id })
        // Primary dies three times: the standby takes over.
        repeat(3) { s = FluxMemory.recordFailure(s, "netA", p.id, now, FailReason.HTTP_TIMEOUT) }
        assertEquals(listOf(sb.id), FluxPlanner.warm(inp(nodes = nodes, state = s)).map { it.id })
        // Nothing known on a network never seen.
        assertTrue(FluxPlanner.warm(inp(nodes = nodes, state = s).copy(net = "netB")).isEmpty())
    }

    @Test fun `a cloudflare node is raced plain, with ECH and with PattNG's cut hello`() {
        val cs = FluxPlanner.candidates(inp(nodes = listOf(cf)).copy(budget = 100))
        val shapes = cs.map { it.fragment }.toSet()
        assertTrue(com.mlmvpn.scanner.engines.flux.core.model.FragmentProfile.ECH in shapes)
        assertTrue(com.mlmvpn.scanner.engines.flux.core.model.FragmentProfile.PATTNG in shapes)
        assertTrue(com.mlmvpn.scanner.engines.flux.core.model.FragmentProfile.OFF in shapes)
    }

    @Test fun `a direct reality node is never given ECH`() {
        val cs = FluxPlanner.candidates(inp(nodes = listOf(rl)))
        assertTrue(cs.none { it.fragment == com.mlmvpn.scanner.engines.flux.core.model.FragmentProfile.ECH })
    }

    @Test fun `the first candidates of a race cover several methods, not only direct servers`() {
        val many = (1..30).map { node(reality(ip = "5.6.7.$it")) } + cf
        val first = FluxPlanner.candidates(inp(nodes = many).copy(budget = 8))
        assertTrue(first.any { it.node.id == cf.id })
        assertTrue(first.map { FluxPlanner.methodOf(it) }.toSet().size >= 3)
    }

    @Test fun `sni resets make the planner try a fragmented twin`() {
        val s = FluxState(nets = mapOf("netA" to com.mlmvpn.scanner.engines.flux.core.memory.NetProfile(sniResets = 2)))
        val cs = FluxPlanner.candidates(inp(nodes = listOf(rl), state = s))
        assertEquals(2, cs.size)
        assertFalse(cs.all { it.fragment == com.mlmvpn.scanner.engines.flux.core.model.FragmentProfile.OFF })
    }
}
