package com.mlmvpn.scanner.widget.studio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioModelTest {

    private fun template(id: String = "w1") = WidgetTemplate(
        id = id, name = "شیشه‌ای · وایرگارد", method = "wireguard", style = StudioStyle.NEON, size = StudioSize.LARGE,
        accent = 0xFF39E58C.toInt(), background = 0xFF0F2236.toInt(), opacity = 37, corner = 30, button = ButtonStyle.GLOW,
        textScale = 110, country = "DE", show = setOf(Info.METHOD, Info.CHART, Info.USAGE), created = 5, updated = 9,
    )

    @Test fun templateSurvivesItsOwnJson() {
        val t = template()
        assertEquals(t, WidgetTemplate.fromJson(JSONObject(t.toJson().toString())))
    }

    @Test fun damagedFieldsAreClampedNotFatal() {
        val o = template().toJson()
            .put("opacity", 900).put("corner", -4).put("textScale", 10)
            .put("style", "no-such-look").put("size", "huge").put("button", 7)
            .put("country", "iran")
        val t = WidgetTemplate.fromJson(o)!!
        assertEquals(WidgetTemplate.OPACITY_MAX, t.opacity)
        assertEquals(WidgetTemplate.CORNER_MIN, t.corner)
        assertEquals(WidgetTemplate.TEXT_MIN, t.textScale)
        assertEquals(StudioStyle.GLASS, t.style)
        assertEquals(StudioSize.MEDIUM, t.size)
        assertEquals(ButtonStyle.RING, t.button)
        // Only a two-letter code is a country; anything else means "the method's own choice".
        assertNull(t.country)
    }

    @Test fun aRecordWithoutIdOrMethodIsDropped() {
        assertNull(WidgetTemplate.fromJson(template().toJson().put("id", "")))
        assertNull(WidgetTemplate.fromJson(template().toJson().apply { remove("method") }))
    }

    @Test fun unknownInfoKeysAreIgnored() {
        val o = template().toJson().put("show", JSONArray(listOf("chart", "hologram", "speed")))
        assertEquals(setOf(Info.CHART, Info.SPEED), WidgetTemplate.fromJson(o)!!.show)
    }

    @Test fun studioDataKeepsBindingsAndDropsDuplicates() {
        val d = StudioData(listOf(template("a"), template("b")), mapOf(12 to "a", 40 to "b"), MethodStyle(card = true, opacity = 70))
        val json = d.toJson()
        // A duplicated design (a hand-edited file) is read once.
        json.getJSONArray("templates").put(template("a").toJson())
        val back = StudioData.fromJson(JSONObject(json.toString()))
        assertEquals(listOf("a", "b"), back.templates.map { it.id })
        assertEquals(mapOf(12 to "a", 40 to "b"), back.bindings)
        assertEquals(MethodStyle(card = true, opacity = 70), back.methodStyle)
        assertEquals("b", back.template(back.bindings[40])!!.id)
    }

    @Test fun everyLookHasOnePresetAndPresetsMakeTemplates() {
        assertEquals(StudioStyle.entries.toSet(), StudioPresets.ALL.map { it.style }.toSet())
        assertEquals(StudioPresets.ALL.size, StudioPresets.ALL.map { it.style }.distinct().size)
        val t = StudioPresets.of(StudioStyle.WAVE).toTemplate("x", "psiphon", "n", 1)
        assertEquals(StudioStyle.WAVE, t.style)
        assertEquals("psiphon", t.method)
        assertNull(t.country)
        assertTrue(StudioPresets.ACCENTS.isNotEmpty() && StudioPresets.BACKGROUNDS.isNotEmpty())
    }
}
