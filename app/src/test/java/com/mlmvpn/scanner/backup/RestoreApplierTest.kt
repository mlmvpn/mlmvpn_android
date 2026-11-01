package com.mlmvpn.scanner.backup

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RestoreApplierTest {

    /** SharedPreferences as maps; a file "exists" once written, as on Android. */
    private class FakePrefs : PrefsIo {
        val files = LinkedHashMap<String, MutableMap<String, Any>>()
        var failOn: String? = null

        override fun exists(name: String) = files.containsKey(name)
        override fun read(name: String): Map<String, Any?> = HashMap(files[name].orEmpty())
        override fun write(name: String, clear: Boolean, puts: Map<String, Any>, removes: Set<String>) {
            if (name == failOn) error("disk full")
            val m = if (clear) LinkedHashMap() else LinkedHashMap(files[name].orEmpty())
            removes.forEach { m.remove(it) }
            m.putAll(puts)
            files[name] = m
        }
        override fun delete(name: String) {
            files.remove(name)
        }
    }

    private lateinit var root: File
    private lateinit var prefs: FakePrefs
    private lateinit var roots: Map<String, File>
    private val work get() = File(root, "no_backup/backup")

    private fun applier() = RestoreApplier(prefs, roots, work)
    private fun f(path: String) = applier().resolve(path)

    @Before
    fun setUp() {
        root = Files.createTempDirectory("restore").toFile()
        roots = mapOf("files" to File(root, "files"), "no_backup" to File(root, "no_backup"), "databases" to File(root, "databases"))
        roots.values.forEach { it.mkdirs() }
        prefs = FakePrefs()
        // The phone as it is before the restore.
        prefs.files["settings"] = linkedMapOf("keep" to "me", "tor_mode" to "auto", "n" to 1)
        prefs.files["shared"] = linkedMapOf("a" to true)
        f("files/state.json").apply { parentFile.mkdirs(); writeText("{\"old\":true}") }
        f("files/state.json.bak").writeText("{\"stale\":true}")
        f("files/dir/one.txt").apply { parentFile.mkdirs(); writeText("1") }
        f("files/dir/sub/two.txt").apply { parentFile.mkdirs(); writeText("2") }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun plan(): RestorePlan = RestorePlan().apply {
        prefs("settings") { put("tor_mode", "obfs4"); put("count", 5L); remove("n") }
        prefs("fresh", clear = true) { put("x", setOf("1", "2")) }
        putFile("files/state.json", "{\"new\":true}".toByteArray())
        putFile("files/new/deep/file.bin", byteArrayOf(1, 2, 3))
        deleteFile("files/dir")
    }

    private fun assertUntouched() {
        assertEquals(mapOf("keep" to "me", "tor_mode" to "auto", "n" to 1), prefs.files["settings"])
        assertEquals(mapOf("a" to true), prefs.files["shared"])
        assertFalse(prefs.exists("fresh"))
        assertEquals("{\"old\":true}", f("files/state.json").readText())
        assertEquals("{\"stale\":true}", f("files/state.json.bak").readText())
        assertFalse(f("files/new/deep/file.bin").exists())
        assertEquals("1", f("files/dir/one.txt").readText())
        assertEquals("2", f("files/dir/sub/two.txt").readText())
    }

    @Test
    fun `a staged restore is applied at the next launch, and only then`() {
        applier().stage(plan().ops, listOf("تور"), 10L)
        assertUntouched()
        assertEquals(RestoreApplier.Phase.STAGED, applier().journal()!!.phase)

        val done = applier().resume(20L)!!
        assertEquals(RestoreApplier.Result.OK, done.result)
        assertEquals(listOf("تور"), done.titles)
        assertEquals(mapOf("keep" to "me", "tor_mode" to "obfs4", "count" to 5L), prefs.files["settings"])
        assertEquals(mapOf("x" to setOf("1", "2")), prefs.files["fresh"])
        assertEquals("{\"new\":true}", f("files/state.json").readText())
        assertFalse("AtomicFile's stale copy would win over the restored file", f("files/state.json.bak").exists())
        assertArrayEquals(byteArrayOf(1, 2, 3), f("files/new/deep/file.bin").readBytes())
        assertFalse(f("files/dir").exists())
        // Nothing is left behind but the journal, and a second launch does nothing.
        assertFalse(File(work, "pending").exists())
        assertFalse(File(work, "rollback").exists())
        assertNull(applier().resume(30L))
        assertEquals(RestoreApplier.Result.OK, applier().journal()!!.result)
    }

    @Test
    fun `a write that fails puts every byte back`() {
        prefs.failOn = "fresh"
        applier().stage(plan().ops, emptyList(), 1L)
        val done = applier().resume(2L)!!
        assertEquals(RestoreApplier.Result.ROLLED_BACK, done.result)
        assertTrue(done.error!!.contains("disk full"))
        prefs.failOn = null
        assertUntouched()
    }

    @Test
    fun `a failure late in the list undoes the early writes too`() {
        val a = applier().apply { beforeOp = { if (it == 4) error("boom") } }
        a.stage(plan().ops, emptyList(), 1L)
        assertEquals(RestoreApplier.Result.ROLLED_BACK, a.resume(2L)!!.result)
        assertUntouched()
    }

    @Test
    fun `the app dying half-way is rolled back at the launch after`() {
        val dying = applier().apply { beforeOp = { if (it == 3) throw RestoreApplier.ProcessDeath() } }
        dying.stage(plan().ops, emptyList(), 1L)
        try {
            dying.resume(2L)
            fail("the process should have died")
        } catch (_: RestoreApplier.ProcessDeath) {
        }
        // Half applied right now.
        assertEquals("obfs4", prefs.files["settings"]!!["tor_mode"])
        assertEquals(RestoreApplier.Phase.APPLYING, applier().journal()!!.phase)

        val next = applier().resume(3L)!!
        assertEquals(RestoreApplier.Result.ROLLED_BACK, next.result)
        assertUntouched()
    }

    @Test
    fun `dying before the copy is complete leaves the restore to be done again`() {
        // The journal still says STAGED: nothing was written, so the next launch simply applies it.
        applier().stage(plan().ops, emptyList(), 1L)
        File(work, "rollback").mkdirs()
        File(work, "rollback/r0").writeText("partial copy")
        assertEquals(RestoreApplier.Result.OK, applier().resume(2L)!!.result)
        assertEquals("obfs4", prefs.files["settings"]!!["tor_mode"])
    }

    @Test
    fun `a staged restore can be dropped before the restart picks it up`() {
        applier().stage(plan().ops, emptyList(), 1L)
        assertTrue(applier().unstage())
        assertNull(applier().resume(2L))
        assertUntouched()
    }

    @Test
    fun `paths cannot leave the app's folders`() {
        for (bad in listOf("files/../x", "files/./x", "/files/x", "cache/x", "files/", "files", "files//x", "shared_prefs/a.xml", "files\\x")) {
            assertTrue(bad, runCatching { RestorePlan().putFile(bad, ByteArray(0)) }.isFailure)
            assertTrue(bad, runCatching { applier().resolve(bad) }.isFailure)
        }
        RestorePlan().putFile("databases/studio.db", ByteArray(0))
        RestorePlan().deleteFile("no_backup/openvpn/catalog")
    }

    @Test
    fun `an item editing a file another item already planned builds on that one`() {
        val p = RestorePlan()
        assertNull(p.planned("files/a.json"))
        p.putFile("files/a.json", "1".toByteArray())
        p.putFile("files/a.json", "2".toByteArray())
        assertEquals("2", String(p.planned("files/a.json")!!))
        p.deleteFile("files/a.json")
        assertEquals(0, p.planned("files/a.json")!!.size)
    }

    @Test
    fun `prefs values are only the six SharedPreferences types`() {
        val p = RestorePlan()
        assertTrue(runCatching { p.prefs("x") { put("k", listOf(1)) } }.isFailure)
        assertTrue(runCatching { p.prefs("x") { put("k", Float.NaN) } }.isFailure)
        assertTrue(runCatching { p.prefs("x") { put("k", setOf(1)) } }.isFailure)
        assertTrue(runCatching { p.prefs("../x") { put("k", 1) } }.isFailure)
        p.prefs("x") { put("k", 1); remove("k") }
        val op = p.ops.single() as RestorePlan.Op.Prefs
        assertEquals(emptyMap<String, Any>(), op.puts)
        assertEquals(setOf("k"), op.removes)
    }
}
