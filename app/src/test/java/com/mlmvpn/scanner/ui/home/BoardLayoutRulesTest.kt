package com.mlmvpn.scanner.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home board's folder rules. Everything here runs on EVERY launch against whatever a user saved
 * in an older release, so each case is an upgrade someone will actually go through.
 */
class BoardLayoutRulesTest {

    private val tools = listOf("fixed_ip", "sublink", "lan", "usage", "tutorial")
    private val known = listOf(
        "masque", "wireguard", "psiphon", "tor", "quick", "fixed_ip", "sublink", "lan",
        "store", "usage", "tutorial", "emergency_2",
    )
    private val boardDefault = listOf("masque", "wireguard", "psiphon", "tor", "quick", "store", "@tools", "emergency_2")
    private val defaults = mapOf("tools" to tools)

    private fun reconcile(order: List<String>?, folders: Map<String, FolderRecord>?, migrated: Boolean) =
        BoardLayoutRules.reconcile(order, folders, migrated, known, boardDefault, defaults)

    private fun everyId(layout: BoardLayout): List<String> =
        layout.order.flatMap { id ->
            if (BoardLayoutRules.isFolderRef(id)) layout.folders.getValue(BoardLayoutRules.folderKey(id)).apps
            else listOf(id)
        }

    @Test
    fun freshInstallGetsTheToolsFolder() {
        val l = reconcile(null, null, migrated = false)
        assertEquals(boardDefault, l.order)
        assertEquals(tools, l.folders.getValue("tools").apps)
        assertNull(l.folders.getValue("tools").title)
        assertEquals(known.sorted(), everyId(l).sorted())
    }

    @Test
    fun anOldBoardMovesItsToolsIntoTheFolderOnce() {
        // Arranged before folders existed: the tools sit on the board, one of them first.
        val saved = listOf("usage", "masque", "fixed_ip", "tor", "sublink", "lan", "tutorial", "store")
        val l = reconcile(saved, null, migrated = false)
        // The folder takes the place of the first tool; the tools leave the top level.
        assertEquals("@tools", l.order.first())
        tools.forEach { assertFalse(it, it in l.order) }
        assertEquals(tools, l.folders.getValue("tools").apps)
        // Nothing lost, nothing doubled.
        assertEquals(known.sorted(), everyId(l).sorted())
    }

    @Test
    fun theMoveDoesNotRepeatAfterTheUserTakesSomethingOut() {
        // Migrated already; the user then dragged "lan" back onto the board.
        val saved = listOf("masque", "lan", "@tools", "tor")
        val folders = mapOf("tools" to FolderRecord(null, listOf("fixed_ip", "sublink", "usage", "tutorial")))
        val l = reconcile(saved, folders, migrated = true)
        assertTrue("lan" in l.order)
        assertFalse("lan" in l.folders.getValue("tools").apps)
    }

    @Test
    fun aRemovedDestinationLeavesNoGapAnywhere() {
        val saved = listOf("masque", "emergency_vercel", "@tools")
        val folders = mapOf("tools" to FolderRecord(null, listOf("fixed_ip", "gone_tool")))
        val l = reconcile(saved, folders, migrated = true)
        assertFalse("emergency_vercel" in l.order)
        assertFalse("gone_tool" in l.folders.getValue("tools").apps)
    }

    @Test
    fun aNewToolGoesIntoTheFolderTheUserStillHas() {
        // Saved when the folder held only two tools; the release added the rest.
        val saved = listOf("masque", "wireguard", "psiphon", "tor", "quick", "store", "@tools", "emergency_2")
        val folders = mapOf("tools" to FolderRecord("Mine", listOf("usage", "fixed_ip")))
        val l = reconcile(saved, folders, migrated = true)
        val inFolder = l.folders.getValue("tools").apps
        assertEquals(listOf("usage", "fixed_ip"), inFolder.take(2))
        assertTrue(inFolder.containsAll(tools))
        assertEquals("Mine", l.folders.getValue("tools").title)
    }

    @Test
    fun anEmptyFolderIsNoFolder() {
        val saved = listOf("masque", "@tools")
        val folders = mapOf("tools" to FolderRecord(null, emptyList()), "f1" to FolderRecord(null, listOf("nope")))
        val l = reconcile(saved, folders, migrated = true)
        assertFalse("@tools" in l.order)
        assertFalse("@f1" in l.order)
        // The tools the deleted folder would have held come back onto the board.
        assertTrue(l.order.containsAll(tools))
    }

    @Test
    fun oneDestinationLivesInOnePlace() {
        val saved = listOf("masque", "lan", "@a", "@b")
        val folders = mapOf(
            "a" to FolderRecord(null, listOf("lan", "tor")),
            "b" to FolderRecord(null, listOf("tor", "quick")),
        )
        val l = reconcile(saved, folders, migrated = true)
        val all = everyId(l)
        assertEquals(all.size, all.toSet().size)
        assertEquals(listOf("lan", "tor"), l.folders.getValue("a").apps)
        assertEquals(listOf("quick"), l.folders.getValue("b").apps)
    }

    @Test
    fun droppingAnAppOnAnAppMakesAFolderInItsPlace() {
        val base = BoardLayout(listOf("masque", "tor", "quick"), emptyMap())
        val l = BoardLayoutRules.merge(base, draggedId = "quick", targetId = "masque", newKey = "fnew")
        assertNotNull(l)
        assertEquals(listOf("@fnew", "tor"), l!!.order)
        assertEquals(listOf("masque", "quick"), l.folders.getValue("fnew").apps)
    }

    @Test
    fun droppingAnAppOnAFolderPutsItInside() {
        val base = BoardLayout(listOf("masque", "@tools"), mapOf("tools" to FolderRecord(null, listOf("lan"))))
        val l = BoardLayoutRules.merge(base, "masque", "@tools", "unused")!!
        assertEquals(listOf("@tools"), l.order)
        assertEquals(listOf("lan", "masque"), l.folders.getValue("tools").apps)
    }

    @Test
    fun aFolderIsNeverPutInsideAnother() {
        val base = BoardLayout(listOf("@a", "@b"), mapOf("a" to FolderRecord(null, listOf("x")), "b" to FolderRecord(null, listOf("y"))))
        assertNull(BoardLayoutRules.merge(base, "@a", "@b", "k"))
    }

    @Test
    fun movingTheLastAppOutDissolvesTheFolderInPlace() {
        val base = BoardLayout(listOf("masque", "@tools", "tor"), mapOf("tools" to FolderRecord(null, listOf("lan"))))
        val l = BoardLayoutRules.moveOut(base, "tools", "lan")!!
        assertEquals(listOf("masque", "lan", "tor"), l.order)
        assertTrue(l.folders.isEmpty())
    }

    @Test
    fun movingOneAppOutPutsItBesideTheFolder() {
        val base = BoardLayout(listOf("masque", "@tools", "tor"), mapOf("tools" to FolderRecord(null, listOf("lan", "usage"))))
        val l = BoardLayoutRules.moveOut(base, "tools", "lan")!!
        assertEquals(listOf("masque", "@tools", "lan", "tor"), l.order)
        assertEquals(listOf("usage"), l.folders.getValue("tools").apps)
    }

    // ---------------------------------------------------------------- drag and drop

    private val board = BoardLayout(
        listOf("masque", "tor", "@tools", "quick"),
        mapOf("tools" to FolderRecord("Mine", listOf("lan", "usage"))),
    )

    @Test
    fun liftingFromTheBoardLeavesTheRestInOrder() {
        val l = BoardLayoutRules.lift(board, "tor")!!
        assertEquals(listOf("masque", "@tools", "quick"), l.order)
        assertEquals(board.folders, l.folders)
    }

    @Test
    fun liftingTheLastAppOfAFolderKeepsItUntilSettled() {
        val one = BoardLayout(listOf("masque", "@tools"), mapOf("tools" to FolderRecord(null, listOf("lan"))))
        val l = BoardLayoutRules.lift(one, "lan")!!
        // Still there while the app is in the air: it may be the folder open on screen.
        assertEquals(listOf("masque", "@tools"), l.order)
        assertTrue(l.folders.getValue("tools").apps.isEmpty())
        // And gone the moment anything is put down.
        val s = BoardLayoutRules.settle(l)
        assertEquals(listOf("masque"), s.order)
        assertTrue(s.folders.isEmpty())
    }

    @Test
    fun liftingSomethingThatIsNotThereIsRefused() {
        assertNull(BoardLayoutRules.lift(board, "nope"))
        assertNull(BoardLayoutRules.lift(board, "@nope"))
    }

    @Test
    fun aBoardDropLandsInTheGapTheUserSaw() {
        // "quick" (last) dragged to the front.
        val l = BoardLayoutRules.lift(board, "quick")!!
        val d = BoardLayoutRules.dropOnBoard(l, "quick", 0)!!
        assertEquals(listOf("quick", "masque", "tor", "@tools"), d.order)
        // Out of range means the end, never an exception.
        assertEquals("quick", BoardLayoutRules.dropOnBoard(l, "quick", 99)!!.order.last())
    }

    @Test
    fun aFolderMovesOnTheBoardWithItsContents() {
        val l = BoardLayoutRules.lift(board, "@tools")!!
        val d = BoardLayoutRules.dropOnBoard(l, "@tools", 0)!!
        assertEquals(listOf("@tools", "masque", "tor", "quick"), d.order)
        assertEquals(listOf("lan", "usage"), d.folders.getValue("tools").apps)
        assertEquals("Mine", d.folders.getValue("tools").title)
    }

    @Test
    fun draggingTheLastAppOutOfAFolderRemovesItAndShiftsNothingElse() {
        val one = BoardLayout(listOf("masque", "@tools", "tor"), mapOf("tools" to FolderRecord(null, listOf("lan"))))
        val l = BoardLayoutRules.lift(one, "lan")!!
        // On screen while it was in the air: masque, tor -- the emptied folder already gone.
        val d = BoardLayoutRules.dropOnBoard(l, "lan", 1)!!
        assertEquals(listOf("masque", "lan", "tor"), d.order)
        assertTrue(d.folders.isEmpty())
    }

    @Test
    fun anAppDraggedOutOfAFolderCanLandAnywhereOnTheBoard() {
        val l = BoardLayoutRules.lift(board, "usage")!!
        val d = BoardLayoutRules.dropOnBoard(l, "usage", 1)!!
        assertEquals(listOf("masque", "usage", "tor", "@tools", "quick"), d.order)
        assertEquals(listOf("lan"), d.folders.getValue("tools").apps)
    }

    @Test
    fun reorderingInsideAFolderIsALiftAndADropIntoIt() {
        val l = BoardLayoutRules.lift(board, "usage")!!
        val d = BoardLayoutRules.dropIntoFolder(l, "usage", "tools", 0)!!
        assertEquals(listOf("usage", "lan"), d.folders.getValue("tools").apps)
        assertEquals(board.order, d.order)
    }

    @Test
    fun theOnlyAppOfAFolderCanBePutStraightBackIntoIt() {
        val one = BoardLayout(listOf("masque", "@tools"), mapOf("tools" to FolderRecord("Mine", listOf("lan"))))
        val l = BoardLayoutRules.lift(one, "lan")!!
        val d = BoardLayoutRules.dropIntoFolder(l, "lan", "tools", 0)!!
        assertEquals(one, d)
    }

    @Test
    fun droppingOntoAFolderTileAppendsAndOntoAnotherFolderMoves() {
        val two = BoardLayout(
            listOf("masque", "@a", "@b"),
            mapOf("a" to FolderRecord(null, listOf("lan", "usage")), "b" to FolderRecord(null, listOf("tor"))),
        )
        val l = BoardLayoutRules.lift(two, "masque")!!
        assertEquals(listOf("tor", "masque"), BoardLayoutRules.dropIntoFolder(l, "masque", "b")!!.folders.getValue("b").apps)
        // From one folder into another, the source keeping the rest.
        val m = BoardLayoutRules.lift(two, "usage")!!
        val d = BoardLayoutRules.dropIntoFolder(m, "usage", "b", 0)!!
        assertEquals(listOf("usage", "tor"), d.folders.getValue("b").apps)
        assertEquals(listOf("lan"), d.folders.getValue("a").apps)
    }

    @Test
    fun droppingAnAppOnAnAppAfterLiftingMakesTheFolderWhereTheTargetWas() {
        val l = BoardLayoutRules.lift(board, "quick")!!
        val d = BoardLayoutRules.dropOnApp(l, "quick", "tor", "c_connect_k1")!!
        assertEquals(listOf("masque", "@c_connect_k1", "@tools"), d.order)
        assertEquals(listOf("tor", "quick"), d.folders.getValue("c_connect_k1").apps)
        assertNull(d.folders.getValue("c_connect_k1").title)
    }

    @Test
    fun theLastAppOfAFolderDroppedOnAnAppLeavesNoEmptyFolderBehind() {
        val one = BoardLayout(listOf("masque", "@tools", "tor"), mapOf("tools" to FolderRecord(null, listOf("lan"))))
        val l = BoardLayoutRules.lift(one, "lan")!!
        val d = BoardLayoutRules.dropOnApp(l, "lan", "tor", "fnew")!!
        assertEquals(listOf("masque", "@fnew"), d.order)
        assertEquals(setOf("fnew"), d.folders.keys)
    }

    @Test
    fun dropsThatMakeNoSenseAreRefused() {
        val l = BoardLayoutRules.lift(board, "@tools")!!
        // A folder never goes into a folder, nor makes one.
        assertNull(BoardLayoutRules.dropIntoFolder(l, "@tools", "tools"))
        assertNull(BoardLayoutRules.dropOnApp(l, "@tools", "tor", "k"))
        val m = BoardLayoutRules.lift(board, "tor")!!
        assertNull(BoardLayoutRules.dropOnApp(m, "tor", "tor", "k"))
        assertNull(BoardLayoutRules.dropOnApp(m, "tor", "masque", "tools"))
        assertNull(BoardLayoutRules.dropOnApp(m, "tor", "masque", "Bad Key!"))
        // Something not actually in the air cannot be dropped a second time.
        assertNull(BoardLayoutRules.dropOnBoard(board, "masque", 0))
    }

    @Test
    fun removingAFolderPutsItsAppsBackWhereItWas() {
        val d = BoardLayoutRules.ungroup(board, "tools")!!
        assertEquals(listOf("masque", "tor", "lan", "usage", "quick"), d.order)
        assertTrue(d.folders.isEmpty())
        assertNull(BoardLayoutRules.ungroup(board, "nope"))
    }

    @Test
    fun theFactoryNameIsNotStoredAsText() {
        val base = BoardLayout(listOf("@tools"), mapOf("tools" to FolderRecord("Old", listOf("lan"))))
        assertNull(BoardLayoutRules.rename(base, "tools", " Tools ", factoryTitle = "Tools")!!.folders.getValue("tools").title)
        assertNull(BoardLayoutRules.rename(base, "tools", "   ", factoryTitle = "Tools")!!.folders.getValue("tools").title)
        assertEquals("Work", BoardLayoutRules.rename(base, "tools", "Work", factoryTitle = "Tools")!!.folders.getValue("tools").title)
    }

    // ------------------------------------------------------------------ a new icon onto page one
    //
    // Four to a page here; the store uses the phone's 24. «لنترن» (1.2.42) came in at the end of an
    // arranged board, on its second page.

    private val arranged = BoardLayout(
        listOf("scout", "masque", "tor", "store", "iran", "@tools", "lantern"),
        mapOf("tools" to FolderRecord(null, listOf("lan", "usage"))),
    )

    @Test
    fun aNewIconTakesTheLastCellOfPageOneAndOnlyThatIconMoves() {
        val l = BoardLayoutRules.promoteToFirstPage(arranged, "lantern", perPage = 4)!!
        // The icon in the last cell starts page two; the rest of page two follows it, unmoved.
        assertEquals(listOf("scout", "masque", "tor", "lantern", "store", "iran", "@tools"), l.order)
        assertEquals(arranged.folders, l.folders)
        assertEquals(everyId(arranged).sorted(), everyId(l).sorted())
    }

    @Test
    fun aKeptIconHoldsItsCellAndTheOneBeforeItIsUsed() {
        // Scout was lifted to the last cell of page one by an earlier release.
        val board = BoardLayout(listOf("masque", "tor", "store", "scout", "iran", "lantern"), emptyMap())
        val l = BoardLayoutRules.promoteToFirstPage(board, "lantern", perPage = 4, keep = setOf("scout"))!!
        assertEquals(listOf("masque", "tor", "lantern", "scout", "store", "iran"), l.order)
    }

    @Test
    fun nothingMovesWhenTheIconIsAlreadyOnPageOneOrInAFolder() {
        val onPageOne = BoardLayout(listOf("masque", "lantern", "tor", "store", "iran"), emptyMap())
        assertNull(BoardLayoutRules.promoteToFirstPage(onPageOne, "lantern", perPage = 4))
        val inFolder = BoardLayout(
            listOf("masque", "tor", "store", "iran", "@connect"),
            mapOf("connect" to FolderRecord(null, listOf("lantern", "geph"))),
        )
        assertNull(BoardLayoutRules.promoteToFirstPage(inFolder, "lantern", perPage = 4))
        assertNull(BoardLayoutRules.promoteToFirstPage(arranged, "gone", perPage = 4))
    }

    @Test
    fun twoNewIconsBothReachPageOneAndTheEarlierOnesStay() {
        // «URnetwork» and «NPV» came in together, after «لنترن» had been lifted to page one.
        val board = BoardLayout(listOf("masque", "tor", "store", "lantern", "iran", "urnetwork", "npv"), emptyMap())
        val keep = setOf("scout", "lantern", "urnetwork", "npv")
        val one = BoardLayoutRules.promoteToFirstPage(board, "urnetwork", perPage = 4, keep = keep)!!
        val two = BoardLayoutRules.promoteToFirstPage(one, "npv", perPage = 4, keep = keep)!!
        assertEquals(listOf("masque", "npv", "urnetwork", "lantern", "tor", "store", "iran"), two.order)
        assertEquals(everyId(board).sorted(), everyId(two).sorted())
    }

    @Test
    fun aFolderInTheLastCellMovesToPageTwoWhole() {
        val board = BoardLayout(listOf("masque", "tor", "store", "@tools", "iran", "lantern"),
            mapOf("tools" to FolderRecord("Mine", listOf("lan"))))
        val l = BoardLayoutRules.promoteToFirstPage(board, "lantern", perPage = 4)!!
        assertEquals(listOf("masque", "tor", "store", "lantern", "@tools", "iran"), l.order)
        assertEquals("Mine", l.folders.getValue("tools").title)
    }
}
