package com.mlmvpn.scanner.ui.doctor

import com.mlmvpn.scanner.data.CloudGroup
import com.mlmvpn.scanner.models.VpnNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DoctorScanSelectionTest {
    private fun node(id: String, uri: String) = VpnNode(id, id, uri, "vless")

    @Test
    fun choicesOnlyExposeTheCurrentAccountAndKeepGroupIdentity() {
        val groups = listOf(
            CloudGroup("g1", "account-1", "today", "Panel A", listOf(node("a", "vless://a"))),
            CloudGroup("g2", "account-2", "today", "Other", listOf(node("b", "vless://b"))),
        )

        val choices = doctorScanChoices(groups, "account-1")

        assertEquals(1, choices.size)
        assertEquals("g1", choices.single().groupId)
        assertEquals("Panel A", choices.single().groupTitle)
        assertEquals("vless://a", choices.single().node.uri)
    }

    @Test
    fun selectedChoicesBecomeOneSourceGroupAndDuplicateUrisAreCollapsed() {
        val groups = listOf(
            CloudGroup("g1", "account-1", "today", "Panel A", listOf(node("a", "vless://same"))),
            CloudGroup("g2", "account-1", "today", "Panel B", listOf(node("b", "vless://same"), node("c", "vless://other"))),
        )
        val choices = doctorScanChoices(groups, "account-1")

        val source = doctorScanGroup("account-1", choices, choices.map { it.id }.toSet(), 123L)

        assertNotNull(source)
        assertEquals("doctor_scan_123", source!!.id)
        assertEquals("account-1", source.accountId)
        assertEquals(listOf("vless://same", "vless://other"), source.nodes.map { it.uri })
    }

    @Test
    fun emptySelectionDoesNotCreateAGroup() {
        val groups = listOf(CloudGroup("g1", "account-1", "today", "Panel", listOf(node("a", "vless://a"))))
        val choices = doctorScanChoices(groups, "account-1")

        assertNull(doctorScanGroup("account-1", choices, emptySet(), 123L))
    }
}
