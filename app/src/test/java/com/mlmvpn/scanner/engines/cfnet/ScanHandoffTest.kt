package com.mlmvpn.scanner.engines.cfnet

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ScanHandoffTest {
    private val profile = NetworkProfile("line", hasV6 = false)
    private val registry = StrategyRegistry.default()
    private val candidates = registry.forStage(CfStage.CONNECT)
    private val fragment = candidates.first { it.data?.shape == TunnelShape.FRAG_FP && it.data?.family == Family.V4 }
    private val now = 1_000_000L
    private fun store() = StrategyStore(Files.createTempDirectory("scan-handoff").resolve("strategies.json").toFile())

    @Test fun `scan winner is a runtime candidate without inventing connect success`() {
        val store = store()
        store.record(profile, CfStage.SCAN, fragment.id, true, 120, null, now)
        val choice = store.runtimePath(profile, CfStage.CONNECT, candidates, now = now + 1)
        assertEquals(fragment.id, choice?.strategy?.id)
        assertEquals(CfStage.SCAN, choice?.source)
        assertNull(store.fastPath(profile, CfStage.CONNECT, candidates, now + 1))
        assertEquals(0, store.stat(profile, CfStage.CONNECT, fragment.id).success)
    }

    @Test fun `scan evidence cannot leak to another network or survive a connect failure`() {
        val store = store()
        store.record(profile, CfStage.SCAN, fragment.id, true, 120, null, now)
        assertNull(store.runtimePath(profile.copy(key = "other"), CfStage.CONNECT, candidates, now = now + 1))
        assertNull(store.runtimePath(profile.copy(hasV6 = true), CfStage.CONNECT, candidates, now = now + 1))
        store.record(profile, CfStage.CONNECT, fragment.id, false, 0, FailureClass.DATA_TRANSFER_FAILURE, now + 2)
        assertNull(store.runtimePath(profile, CfStage.CONNECT, candidates, now = now + 3))
    }

    @Test fun `expired scan evidence and unrelated stages do not become runtime winners`() {
        val store = store()
        store.record(profile, CfStage.SCAN, fragment.id, true, 120, null, now)
        assertNull(store.runtimePath(profile, CfStage.CONNECT, candidates, now = now + 21 * 60_000))
        assertNull(store.runtimePath(profile, CfStage.CONFIG, registry.forStage(CfStage.CONFIG), now = now + 1))
        assertNull(store.runtimePath(profile, CfStage.CONNECT, candidates.filter { it.id != fragment.id }, now = now + 1))
    }

    @Test fun `connect winner keeps precedence over a scan candidate`() {
        val store = store()
        val chrome = candidates.first { it.data?.shape == TunnelShape.CHROME }
        store.record(profile, CfStage.SCAN, fragment.id, true, 120, null, now)
        store.record(profile, CfStage.CONNECT, chrome.id, true, 150, null, now)
        val choice = store.runtimePath(profile, CfStage.CONNECT, candidates, now = now + 1)
        assertEquals(chrome.id, choice?.strategy?.id)
        assertEquals(CfStage.CONNECT, choice?.source)
    }

    @Test fun `failure on another signature does not reject this networks scan route`() {
        val store = store()
        store.record(profile, CfStage.SCAN, fragment.id, true, 120, null, now)
        store.record(profile.copy(dnsSane = false), CfStage.CONNECT, fragment.id, false, 0, FailureClass.TLS_FAILURE, now + 2)
        assertEquals(fragment.id, store.runtimePath(profile, CfStage.CONNECT, candidates, now = now + 3)?.strategy?.id)
    }

    // A clean-IP config (a combine on scanned addresses) is dialled with the handshake that SCAN
    // proved on such addresses -- ahead of a CONNECT winner proven on a name, and long after the
    // 20-minute hand-off a name gets.
    @Test fun `a literal address takes the scan winner first and keeps it past the hand-off window`() {
        val store = store()
        val chrome = candidates.first { it.data?.shape == TunnelShape.CHROME && it.data?.family == Family.V4 }
        store.record(profile, CfStage.SCAN, fragment.id, true, 120, null, now)
        store.record(profile, CfStage.CONNECT, chrome.id, true, 150, null, now)
        val later = now + 3 * 3600_000L
        val literal = store.runtimePath(profile, CfStage.CONNECT, candidates, now = later, literal = true)
        assertEquals(fragment.id, literal?.strategy?.id)
        assertEquals(CfStage.SCAN, literal?.source)
        assertEquals(chrome.id, store.runtimePath(profile, CfStage.CONNECT, candidates, now = later)?.strategy?.id)
    }

    @Test fun `a connect failure after the scan hands a literal address to the connect winner`() {
        val store = store()
        val chrome = candidates.first { it.data?.shape == TunnelShape.CHROME && it.data?.family == Family.V4 }
        store.record(profile, CfStage.SCAN, fragment.id, true, 120, null, now)
        store.record(profile, CfStage.CONNECT, chrome.id, true, 150, null, now)
        store.record(profile, CfStage.CONNECT, fragment.id, false, 0, FailureClass.DATA_TRANSFER_FAILURE, now + 5)
        assertEquals(chrome.id, store.runtimePath(profile, CfStage.CONNECT, candidates, now = now + 6, literal = true)?.strategy?.id)
        // A fresh scan success with it makes it the literal choice again.
        store.record(profile, CfStage.SCAN, fragment.id, true, 110, null, now + 7)
        assertEquals(fragment.id, store.runtimePath(profile, CfStage.CONNECT, candidates, now = now + 8, literal = true)?.strategy?.id)
    }

    @Test fun `other stages ignore the literal flag`() {
        val store = store()
        store.record(profile, CfStage.SCAN, fragment.id, true, 120, null, now)
        val scan = registry.forStage(CfStage.SCAN)
        assertEquals(fragment.id, store.runtimePath(profile, CfStage.SCAN, scan, now = now + 1, literal = true)?.strategy?.id)
        assertNull(store.runtimePath(profile, CfStage.CONFIG, registry.forStage(CfStage.CONFIG), now = now + 1, literal = true))
    }
}
