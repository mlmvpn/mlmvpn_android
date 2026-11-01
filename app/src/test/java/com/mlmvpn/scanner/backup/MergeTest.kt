package com.mlmvpn.scanner.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MergeTest {

    private fun o(id: String, v: String) = JSONObject().put("id", id).put("v", v)
    private fun ids(list: List<JSONObject>) = list.map { it.optString("id") + "=" + it.optString("v") }

    @Test
    fun `the phone keeps its order, the file's copy wins, the file's new ones follow`() {
        val current = listOf(o("a", "phone"), o("b", "phone"), o("c", "phone"))
        val backup = listOf(o("d", "file"), o("b", "file"), o("e", "file"))
        assertEquals(
            listOf("a=phone", "b=file", "c=phone", "d=file", "e=file"),
            ids(mergeById(current, backup) { it.optString("id") }),
        )
    }

    @Test
    fun `entries without a key are kept from both sides`() {
        val current = listOf(o("", "phone"))
        val backup = listOf(o("", "file"), o("x", "file"))
        assertEquals(listOf("=phone", "x=file", "=file"), ids(mergeById(current, backup) { it.optString("id").ifBlank { null } }))
    }

    @Test
    fun `a backup holding one key twice keeps its last copy`() {
        val backup = listOf(o("a", "first"), o("a", "second"))
        assertEquals(listOf("a=second"), ids(mergeById(emptyList(), backup) { it.optString("id") }))
    }
}
