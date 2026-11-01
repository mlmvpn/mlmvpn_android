package com.mlmvpn.scanner.widget

import com.mlmvpn.scanner.ui.home.HomeDestinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The method widgets are declared in four places that must agree: the list of methods, the provider
 * classes, the generated appwidget-provider files, and the manifests. A method added to one and not
 * the others either never shows in the phone's widget picker or shows one that draws nothing.
 */
class WidgetMethodsTest {

    @Test fun everyMethodHasExactlyOneProvider() {
        assertEquals(WidgetMethods.IDS.size, WidgetMethods.IDS.toSet().size)
        assertEquals(WidgetMethods.IDS.toSet(), MethodWidgets.PROVIDERS.keys)
        assertEquals(MethodWidgets.PROVIDERS.size, MethodWidgets.PROVIDERS.values.toSet().size)
    }

    @Test fun everyProviderIsDeclaredWithItsOwnInfo() {
        val main = File("src/main/AndroidManifest.xml")
        assumeTrue("run from the app module", main.isFile)
        val manifests = main.readText() + File("src/debug/AndroidManifest.xml").readText()
        for ((id, cls) in MethodWidgets.PROVIDERS) {
            assertTrue("$id: no info file", File("src/main/res/xml/widget_method_$id.xml").isFile)
            assertTrue("$id: ${cls.simpleName} not in a manifest", manifests.contains(".widget.${cls.simpleName}\""))
            assertTrue("$id: its receiver does not point at its own info", manifests.contains("@xml/widget_method_$id\""))
        }
    }

    /**
     * A release carries the widget of every method it shows, and none of a method still being built
     * (HomeDestinations.IN_PROGRESS). 2026-10-20: «URnetwork» and «NPV» were missing from a release
     * APK -- tile and widget -- while the changelog already announced URnetwork.
     */
    @Test fun aReleaseCarriesTheWidgetOfEveryMethodItShows() {
        val main = File("src/main/AndroidManifest.xml")
        assumeTrue("run from the app module", main.isFile)
        val text = main.readText()
        for ((id, cls) in MethodWidgets.PROVIDERS) {
            val inRelease = text.contains(".widget.${cls.simpleName}\"")
            if (id in HomeDestinations.IN_PROGRESS) assertTrue("$id is still being built", !inRelease)
            else assertTrue("$id: a release must declare ${cls.simpleName}", inRelease)
        }
    }
}
