package com.mlmvpn.scanner.ui.home

import com.mlmvpn.scanner.ui.AppStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the home screen's search finds among the tiles, on a board laid out the way a fresh install has it. */
class HomeSearchTest {

    private val everyId = (HomeDestinations.GRID_DEFAULT + HomeDestinations.DOCK).map { it.id }

    /** A fresh install's board, from the same rules the app reconciles every saved board with. */
    private val board: List<BoardItem> = HomeLayoutStore.toItems(
        BoardLayoutRules.reconcile(
            savedOrder = null,
            savedFolders = null,
            migrated = false,
            known = HomeDestinations.GRID_DEFAULT.map { it.id },
            boardDefault = HomeDestinations.BOARD_DEFAULT,
            defaultFolders = HomeDestinations.DEFAULT_FOLDERS,
        )
    )

    /** The tiles as HomeSearch builds them, names from the app's own strings. */
    private val tiles: List<TileHit> = homeTiles(board, HomeDestinations.DOCK).map { (app, folder) ->
        val folderNames: List<String> = when (folder) {
            null -> emptyList()
            else -> folder.title?.let { listOf(it) }
                ?: HomeDestinations.folderTitleRes(folder.key).let { listOf(AppStrings.fa(it), AppStrings.en(it)) }
        }
        TileHit(
            app,
            folder,
            tileFields(listOf(AppStrings.fa(app.labelRes), AppStrings.en(app.labelRes)), TileAliases[app.id].orEmpty(), folderNames),
        )
    }

    private fun found(query: String): List<String> = SearchMatcher.rank(query, tiles) { it.fields }.map { it.app.id }

    @Test
    fun everyDestinationIsThereOnceTheDockAndTheFoldersIncluded() {
        val ids = homeTiles(board, HomeDestinations.DOCK).map { it.first.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(everyId.toSet(), ids.toSet())
        val byId = homeTiles(board, HomeDestinations.DOCK).associate { it.first.id to it.second }
        HomeDestinations.DEFAULT_FOLDERS.getValue("tools").forEach { assertEquals("tools", byId.getValue(it)?.key) }
        assertNull(byId.getValue("masque"))
        assertNull(byId.getValue("settings"))
    }

    @Test
    fun theBoardsOwnOrderIsKeptAndTheDockComesLast() {
        val ids = homeTiles(board, HomeDestinations.DOCK).map { it.first.id }
        assertEquals(HomeDestinations.DOCK.map { it.id }, ids.takeLast(HomeDestinations.DOCK.size))
        val firstOnBoard = (board.first() as BoardItem.App).app.id
        assertEquals(firstOnBoard, ids.first())
    }

    @Test
    fun everyOtherNameBelongsToARealTile() {
        assertTrue("aliases for tiles that do not exist: ${TileAliases.keys - everyId.toSet()}", (TileAliases.keys - everyId.toSet()).isEmpty())
    }

    @Test
    fun theRightTileComesFirstInEitherLanguage() {
        assertEquals("settings", found("تنظیمات").first())
        assertEquals("settings", found("settings").first())
        assertEquals("warp", found("وارپ").first())
        assertEquals("warp_on_warp", found("وارپ در").first())
        assertEquals("nodes", found("v2ray").first())
        assertEquals("cloud", found("کلادفلر").first())
        assertEquals("wireguard", found("WG").first())
        assertEquals("fixed_ip", found("آی‌پی ثابت").first())
        assertEquals("game", found("بازی").first())
        // As an Arabic keyboard types it, and without the zero-width non-joiner.
        assertEquals("psiphon", found("سايفون").first())
        assertEquals("tutorial", found("آموزشها").first())
        assertTrue(found("zzzz").isEmpty())
    }

    @Test
    fun aFoldersNameFindsWhatIsInIt() {
        assertTrue(found("ابزارها").containsAll(HomeDestinations.DEFAULT_FOLDERS.getValue("tools")))
        assertTrue(found("tools").containsAll(HomeDestinations.DEFAULT_FOLDERS.getValue("tools")))
    }
}
