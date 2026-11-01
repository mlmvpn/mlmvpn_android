package com.mlmvpn.scanner.engines.npv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The public page of a channel (`t.me/s/<channel>`), shaped like the real one (2026-10-18). */
class NpvPageTest {

    private val page = """
        <div class="tgme_channel_info">x</div>
        <div class="tgme_widget_message_wrap js-widget_message_wrap"><div class="tgme_widget_message text_not_supported_wrap js-widget_message" data-post="ConfigsHUB/2190663" data-view="x">
        <div class="tgme_widget_message_text js-message_text" dir="auto"><a href="?q=%23v2ray">#v2ray</a><br/><pre>vless://u-1@146.75.59.186:443?path=%2F&amp;security=tls&amp;type=ws#[<i class="emoji" style="background-image:url('//telegram.org/img/emoji/40/F09F8F81.png')"><b>🏁</b></i>]t.me/ConfigsHub</pre><pre>trojan://p@1.2.3.4:443#two</pre><br/>
        sub: <a href="https://raw.githubusercontent.com/a/b/main/sub.txt">here</a> and https://t.me/ConfigsHub and https://example.com/blog</div>
        <div class="tgme_widget_message_footer compact js-message_footer"><a href="https://t.me/ConfigsHUB/2190663">time</a></div></div></div>
        <div class="tgme_widget_message_wrap js-widget_message_wrap"><div class="tgme_widget_message js-widget_message" data-post="ConfigsHUB/2190664" data-view="y">
        <div class="media_supported_cont"><a class="tgme_widget_message_document_wrap" href="https://t.me/ConfigsHUB/2190664">
          <div class="tgme_widget_message_document_title accent_color" dir="auto">[ FAST ] &amp; free.npvt</div>
          <div class="tgme_widget_message_document_extra" dir="auto">14.6 KB</div>
        </a></div>
        <div class="tgme_widget_message_document_title accent_color" dir="auto">photo.jpg</div>
        <div class="tgme_widget_message_document_extra" dir="auto">1 MB</div>
        <div class="tgme_widget_message_text js-message_text" dir="auto">no links here</div></div></div>
    """.trimIndent()

    @Test
    fun postsCarryTheirIdLinkConfigsAndFiles() {
        val posts = NpvEngine.posts("ConfigsHUB", page) { true }
        assertEquals(listOf(2190663L, 2190664L), posts.map { it.id })
        assertEquals("https://t.me/ConfigsHUB/2190663", posts[0].link)
        // Entities decoded and the emoji's tags gone, so the link is whole; the two <pre> do not run together.
        assertEquals(listOf("vless://u-1@146.75.59.186:443?path=%2F&security=tls&type=ws#[🏁]t.me/ConfigsHub", "trojan://p@1.2.3.4:443#two"), posts[0].links)
        assertEquals(listOf("https://raw.githubusercontent.com/a/b/main/sub.txt"), posts[0].subs)
        assertTrue(posts[0].files.isEmpty())
        // Only the files worth having: an NPV Tunnel file yes, a photo no.
        assertEquals(listOf("[ FAST ] & free.npvt" to "14.6 KB"), posts[1].files)
        assertTrue(posts[1].links.isEmpty())
    }

    @Test
    fun entitiesAreDecoded() {
        assertEquals("!A&<>\"' ", NpvEngine.decodeEntities("&#33;&#x41;&amp;&lt;&gt;&quot;&apos;&nbsp;"))
        assertEquals("&bogus;", NpvEngine.decodeEntities("&bogus;"))
        assertEquals("plain", NpvEngine.decodeEntities("plain"))
    }

    @Test
    fun blockEndsBecomeSpacesSoTwoConfigsNeverMerge() {
        val t = NpvEngine.visibleText("<code>ss://a@h:1#x</code><code>ss://b@h:2#y</code>line<br/>next")
        assertEquals(listOf("ss://a@h:1#x", "ss://b@h:2#y"), NpvEngine.extractLinks(t) { true })
        assertTrue(t.contains("line next"))
    }

    @Test
    fun subscriptionLinksArePublicHttpsListsOnly() {
        val subs = NpvEngine.subLinks(
            "https://raw.githubusercontent.com/x/y/main/all.txt https://panel.example.ir/sub/abc http://plain.example.com/sub " +
                "https://t.me/s/channel https://192.168.1.1/sub https://localhost/sub https://example.com/about",
            listOf("https://gist.githubusercontent.com/u/1/raw/list", "https://telegram.org/sub"),
        )
        assertEquals(
            listOf("https://raw.githubusercontent.com/x/y/main/all.txt", "https://panel.example.ir/sub/abc", "https://gist.githubusercontent.com/u/1/raw/list"),
            subs,
        )
    }

    @Test
    fun wantedFiles() {
        listOf("a.npvt", "B.NPVS", "list.txt", "c.conf", "d.json", "e.yaml").forEach { assertTrue(it, NpvEngine.isWantedFile(it)) }
        listOf("photo.jpg", "app.apk", "video.mp4", "npvt").forEach { assertFalse(it, NpvEngine.isWantedFile(it)) }
    }

    @Test
    fun channelAddresses() {
        assertEquals("ConfigsHUB", NpvEngine.channelOf("https://t.me/s/ConfigsHUB"))
        assertEquals("mitivpn", NpvEngine.channelOf("@mitivpn"))
        assertEquals("abc_def", NpvEngine.channelOf("tg://resolve?domain=abc_def"))
        assertEquals("AzadNet", NpvEngine.channelOf("AzadNet"))
        assertNull(NpvEngine.channelOf("https://t.me/joinchat/AAAA"))
        assertNull(NpvEngine.channelOf("not a channel"))
    }
}
