package com.mlmvpn.scanner.engines.scout

import com.mlmvpn.scanner.ui.home.HomeDestinations
import org.junit.Assert.*
import org.junit.Test

class ScoutCoverageTest {
    @Test fun `every home destination has an adapter or an explicit exclusion`() {
        val ids = (HomeDestinations.DOCK + HomeDestinations.GRID_DEFAULT).map { it.id }.toSet()
        val adapters = ScoutRegistry.ALL
        val covered = adapters.map { it.homeId }.toSet() + ScoutRegistry.EXCLUDED.keys
        assertEquals("Home destinations absent from Scout", emptySet<String>(), ids - covered)
        assertEquals("Duplicate Scout method ids", adapters.size, adapters.map { it.id }.distinct().size)
        assertTrue(ScoutRegistry.EXCLUDED.values.all { it.isNotBlank() })
        assertTrue(adapters.all { it.homeId in ids })
    }
    @Test fun `built data adapters implement preparation instead of the placeholder`() {
        val placeholder = ScoutAdapter::class.java
        for (adapter in ScoutRegistry.ALL.filter { it.built && it.evidence == Evidence.DATA }) {
            val prepare = adapter.javaClass.methods.first { it.name == "prepare" }
            assertNotEquals("${adapter.id} still uses the placeholder", placeholder, prepare.declaringClass)
            val serve = adapter.javaClass.methods.first { it.name == "serve" }
            assertNotEquals("${adapter.id} has no local proxy", placeholder, serve.declaringClass)
        }
    }
    @Test fun `every method has a real tester, none is left as coming soon`() {
        assertEquals("Methods without a tester", emptyList<String>(), ScoutRegistry.ALL.filter { !it.built }.map { it.id })
    }
    @Test fun `built handshake adapters implement the actual probe`() {
        for (adapter in ScoutRegistry.ALL.filter { it.built && it.evidence == Evidence.HANDSHAKE }) {
            assertNotEquals(adapter.id, ScoutAdapter::class.java, adapter.javaClass.methods.first { it.name == "handshake" }.declaringClass)
        }
    }
}
