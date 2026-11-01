package com.mlmvpn.scanner.engines.game.booster

import com.mlmvpn.scanner.engines.game.booster.model.RouteKind
import com.mlmvpn.scanner.engines.game.booster.session.GameRoute
import com.mlmvpn.scanner.engines.game.booster.session.GameRoute.ProbePath
import com.mlmvpn.scanner.utils.ConnectionOwner
import com.mlmvpn.scanner.utils.GameSessionOwner
import org.junit.Assert.*
import org.junit.Test

class GameRouteTest {
    @Test fun `a queued start is rejected after its boost is cancelled`() {
        val old = "cancelled-boost"
        val next = "next-boost"
        GameSessionOwner.register(old)
        GameSessionOwner.register(next)
        try {
            GameSessionOwner.release(old)
            assertFalse(GameSessionOwner.accepts(old))
            assertTrue(GameSessionOwner.accepts(next))
            assertTrue(GameSessionOwner.accepts(null))
        } finally {
            GameSessionOwner.release(old)
            GameSessionOwner.release(next)
        }
    }

    @Test fun `healthy engine routes survive monitoring`() {
        assertTrue(GameRoute.isUp(RouteKind.AMNEZIA, true, "amnezia"))
        assertTrue(GameRoute.isUp(RouteKind.FENIX, true, "fenix"))
        assertFalse(GameRoute.isUp(RouteKind.AMNEZIA, true, "fenix"))
        assertFalse(GameRoute.isUp(RouteKind.FENIX, false, "fenix"))
        assertFalse(GameRoute.isUp(RouteKind.DIRECT_DNS, true, "game_warp"))
        assertFalse(GameRoute.isUp(RouteKind.WARP_WG, true, "game_dns"))
    }

    @Test fun `monitor uses selected engine transport including native wireguard`() {
        assertEquals(ProbePath.VPN, GameRoute.probePath(RouteKind.AMNEZIA, nativeAmnezia = true))
        assertEquals(ProbePath.SOCKS, GameRoute.probePath(RouteKind.AMNEZIA))
        assertEquals(ProbePath.SOCKS, GameRoute.probePath(RouteKind.FENIX))
        assertEquals(ProbePath.PHYSICAL, GameRoute.probePath(RouteKind.DIRECT_DNS))
    }

    @Test fun `stale cleanup never stops a replacement connection`() {
        val ownership = ConnectionOwner()
        val booster = Any()
        var connected = false
        ownership.replace(booster) { connected = true }
        ownership.replace(null) { connected = true } // the user started another connection
        assertFalse(ownership.stop(booster) { connected = false })
        assertTrue(connected)
        assertTrue(ownership.stop { connected = false })
        assertFalse(connected)
    }

    @Test fun `owned pending connection is cancellable before becoming connected`() {
        val ownership = ConnectionOwner()
        val booster = Any()
        var pending = false
        ownership.replace(booster) { pending = true }
        assertTrue(ownership.stop(booster) { pending = false })
        assertFalse(pending)
        assertFalse(ownership.owns(booster))
    }
}
