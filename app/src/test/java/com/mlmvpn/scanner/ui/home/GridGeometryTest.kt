package com.mlmvpn.scanner.ui.home

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

/** The board's slot arithmetic with the connection widget's rows reserved on the first page. */
class GridGeometryTest {

    private fun geometry(page: Int, reserved: Int, pageCount: Int = 3) = GridGeometry(
        bounds = Rect(0f, 0f, 400f, 600f), columns = 4, rows = 6, page = page, pageCount = pageCount,
        tilePx = 60f, tileTopPx = 10f, rtl = false, reserved = reserved,
    )

    @Test fun withoutTheWidgetItIsThePlainArithmetic() {
        for (page in 0..2) {
            val g = geometry(page, reserved = 0)
            for (slot in 0 until 24) {
                val v = g.visualAt(slot)
                assertEquals(page * 24 + slot, v)
                assertEquals(page, g.pageOf(v))
                assertEquals(slot, g.slotOf(v))
            }
        }
    }

    @Test fun theWidgetsSlotsHoldNoIconAndTheRestFlowOn() {
        val first = geometry(0, reserved = 8)
        for (slot in 0 until 8) assertEquals(-1, first.visualAt(slot))
        // The first icon sits in the first slot after the widget's two rows.
        assertEquals(0, first.visualAt(8))
        assertEquals(15, first.visualAt(23))
        // The second page starts with the seventeenth icon.
        val second = geometry(1, reserved = 8)
        assertEquals(16, second.visualAt(0))
        assertEquals(39, second.visualAt(23))
        assertEquals(40, geometry(2, reserved = 8).visualAt(0))
    }

    @Test fun pageAndSlotInvertVisualAtEverywhere() {
        for (page in 0..2) {
            val g = geometry(page, reserved = 8)
            for (slot in 0 until 24) {
                val v = g.visualAt(slot)
                if (v < 0) continue
                assertEquals("visual $v", page, g.pageOf(v))
                assertEquals("visual $v", slot, g.slotOf(v))
            }
        }
    }
}
