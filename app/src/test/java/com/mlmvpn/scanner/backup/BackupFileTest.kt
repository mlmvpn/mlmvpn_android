package com.mlmvpn.scanner.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class BackupFileTest {

    /** Few rounds: the format is under test here, not the key derivation's cost. */
    private val fast = 2_000

    private fun content() = JSONObject()
        .put("content", 1)
        .put("items", JSONArray().put(JSONObject().put("id", "x").put("data", JSONObject().put("token", "سلام-secret-123"))))

    private fun write(password: String?, c: JSONObject = content()) =
        BackupFile.write(c, password, "1.2.42", 78, 1_760_760_000_000L, iterations = fast)

    @Test
    fun `without a password the file opens without one and carries everything`() {
        val bytes = write(null)
        val r = BackupFile.open(bytes, null) as BackupFile.Opened.Ok
        assertFalse(r.header.encrypted)
        assertEquals("1.2.42", r.header.appVersion)
        assertEquals(78L, r.header.appBuild)
        assertEquals(content().toString(), r.content.toString())
        // A password given to an unencrypted file is simply not needed.
        assertTrue(BackupFile.open(bytes, "anything") is BackupFile.Opened.Ok)
    }

    @Test
    fun `with a password the right one opens it, a wrong one is called wrong, none asks for one`() {
        val bytes = write("my pass 1")
        val ok = BackupFile.open(bytes, "my pass 1") as BackupFile.Opened.Ok
        assertTrue(ok.header.encrypted)
        assertEquals(content().toString(), ok.content.toString())
        assertTrue(BackupFile.open(bytes, "my pass 2") is BackupFile.Opened.WrongPassword)
        assertTrue(BackupFile.open(bytes, null) is BackupFile.Opened.NeedsPassword)
        assertTrue(BackupFile.open(bytes, "   ") is BackupFile.Opened.NeedsPassword)
    }

    @Test
    fun `the secret never appears in an encrypted file`() {
        val text = String(write("p4ss"), Charsets.UTF_8)
        assertFalse(text.contains("secret-123"))
        assertFalse(text.contains("token"))
    }

    @Test
    fun `a password typed on a Persian keyboard opens a file made on an Arabic one`() {
        // Arabic yeh and kaf, Persian digits and a stray zero-width non-joiner on one side;
        // Persian letters and ASCII digits on the other.
        val bytes = write("كيان‌۱۲۳")
        assertTrue(BackupFile.open(bytes, "کیان123") is BackupFile.Opened.Ok)
        assertTrue(BackupFile.open(bytes, " کیان١٢٣ ") is BackupFile.Opened.Ok)
        assertTrue(BackupFile.open(bytes, "کیان124") is BackupFile.Opened.WrongPassword)
    }

    @Test
    fun `a damaged payload is damaged, not a wrong password`() {
        for (password in listOf(null, "pw")) {
            val env = JSONObject(String(write(password), Charsets.UTF_8))
            val payload = B64.decode(env.getString("payload"))
            payload[payload.size / 2] = (payload[payload.size / 2].toInt() xor 1).toByte()
            env.put("payload", B64.encode(payload))
            assertTrue(BackupFile.open(env.toString().toByteArray(), password) is BackupFile.Opened.Corrupt)
        }
    }

    @Test
    fun `a file cut short is damaged, and some other file is not a backup`() {
        val bytes = write("pw")
        assertTrue(BackupFile.open(bytes.copyOf(bytes.size / 2), "pw") is BackupFile.Opened.Corrupt)
        assertTrue(BackupFile.open("hello".toByteArray(), null) is BackupFile.Opened.NotBackup)
        assertTrue(BackupFile.open("{\"a\":1}".toByteArray(), null) is BackupFile.Opened.NotBackup)
        assertTrue(BackupFile.open(ByteArray(0), null) is BackupFile.Opened.NotBackup)
        assertTrue(BackupFile.open(byteArrayOf(0x50, 0x4b, 3, 4), null) is BackupFile.Opened.NotBackup)
    }

    @Test
    fun `a file from a newer envelope is refused as too new, before any password is asked`() {
        val env = JSONObject(String(write("pw"), Charsets.UTF_8)).put(BackupFile.MAGIC, BackupFile.FORMAT + 1)
        val r = BackupFile.open(env.toString().toByteArray(), null)
        assertTrue(r is BackupFile.Opened.TooNew)
        assertEquals("1.2.42", (r as BackupFile.Opened.TooNew).header.appVersion)
    }

    @Test
    fun `two files of the same content differ, so nothing repeats across backups`() {
        val a = JSONObject(String(write("pw"), Charsets.UTF_8))
        val b = JSONObject(String(write("pw"), Charsets.UTF_8))
        assertFalse(a.getString("salt") == b.getString("salt"))
        assertFalse(a.getString("payload") == b.getString("payload"))
    }

    @Test
    fun `PBKDF2-HMAC-SHA256 matches RFC 7914's vectors`() {
        fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(
            hex("55 ac 04 6e 56 e3 08 9f ec 16 91 c2 25 44 b6 05 f9 41 85 21 6d de 04 65 e6 8b 9d 57 c2 0d ac bc 49 ca 9c cc f1 79 b6 45 99 16 64 b3 9d 77 ef 31 7c 71 b8 45 b1 e3 0b d5 09 11 20 41 d3 a1 97 83"),
            BackupFile.pbkdf2("passwd".toByteArray(), "salt".toByteArray(), 1, 64),
        )
        assertArrayEquals(
            hex("4d dc d8 f6 0b 98 be 21 83 0c ee 5e f2 27 01 f9 64 1a 44 18 d0 4c 04 14 ae ff 08 87 6b 34 ab 56 a1 d4 25 a1 22 58 33 54 9a db 84 1b 51 c9 b3 17 6a 27 2b de bb a1 d0 78 47 8f 62 b3 97 f3 3c 8d"),
            BackupFile.pbkdf2("Password".toByteArray(), "NaCl".toByteArray(), 80_000, 64),
        )
    }

    @Test
    fun `the real round count works end to end`() {
        val bytes = BackupFile.write(content(), "real", "1.2.42", 78, 1L)
        assertEquals(BackupFile.KDF_ITERATIONS, JSONObject(String(bytes, Charsets.UTF_8)).getInt("kdf_iterations"))
        assertTrue(BackupFile.open(bytes, "real") is BackupFile.Opened.Ok)
    }

    @Test
    fun `base64 matches the JDK's, both ways, for every length`() {
        val rnd = SecureRandom()
        for (n in 0..200) {
            val data = ByteArray(n).also(rnd::nextBytes)
            val jdk = java.util.Base64.getEncoder().encodeToString(data)
            assertEquals(jdk, B64.encode(data))
            assertArrayEquals(data, B64.decode(jdk))
            assertArrayEquals(data, B64.decode(jdk.chunked(7).joinToString("\n")))
        }
    }

    @Test
    fun `base64 refuses what is not base64`() {
        for (bad in listOf("abc", "ab=c", "a*bc", "====", "ab==cd==")) {
            assertTrue(bad, runCatching { B64.decode(bad) }.isFailure)
        }
    }
}
