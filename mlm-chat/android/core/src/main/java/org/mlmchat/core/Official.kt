package org.mlmchat.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * The official channel and group (T38, SPEC 18), the same in every app that carries Gereh: MLMVPN
 * and Dr. Net (Ehsan, 26 Oct 2026: «کانال‌ها و گروه‌ها در هر دو مشترک باشن»). Their ids and the
 * owner key's public half ship here, in the shared package, so both apps' users are in the same
 * two chats; the owner key's secret half stays offline with the owner and only signs the admin
 * lists below. [MIRROR] is the official Google relay (SPEC 18.2), where a phone with no contact yet
 * reads the posts and the member count.
 *
 * Changing any of these splits the users of the two apps: it goes in both apps' next release.
 * `mlm-chat/tools/field-test/official-feeds.json` holds the same, for peer-cli.
 */
object Official {
    const val CHANNEL = "549ef39b524c96654d0fee24414014b0"
    const val GROUP = "d4249e84b11c00236edf28621d63575e"
    const val OWNER = "b49a6983c341567dee9dcf98beb5c4e2c3e2d2366b865d8c71af1333312cedc8"

    /**
     * The owner's signed admin lists, one per feed (`peer-cli <key> feed-admins`). Version 2 (25 Oct
     * 2026): admin = Ehsan's own identity, ehsan·3599. A newer version replaces an older one for
     * everyone who has both (the biggest version signed by the owner wins).
     */
    @JvmField
    val SEED: List<String> = listOf(
        "0a730a10549ef39b524c96654d0fee24414014b01210d9aeae305a5f2d1dc1eb82048917c4561a20b49a6983c341567dee9dcf98beb5c4e2c3e2d2366b865d8c71af1333312cedc8208494ca9097346a240802122046ede0aaeacb62bcd6abc8642bae8e5488878770e6b0e48e020101d141f29ad91240696ebb95fd773b278fd7d45507a931466a7840910d9b11716557cb1a64702a76f0af8df9f6cd7f2cc0cb7d428166186366e6ac7299ba46aba2221701a9d99e0b",
        "0a730a10d4249e84b11c00236edf28621d63575e1210ab559fc1ef1fae988822a50dc8d319151a20b49a6983c341567dee9dcf98beb5c4e2c3e2d2366b865d8c71af1333312cedc820a094ca9097346a240802122046ede0aaeacb62bcd6abc8642bae8e5488878770e6b0e48e020101d141f29ad912407d6b8d0e65c0f50984da0137d331c1bedf390b7f2ecf4eb9f70f06931a938474d3eacf8d9e294ad28b24358f7381511478a5329c4fee084bbc817fc68ac0cd09",
    )

    /** The official Google relay's share link (`mlmchat://r/…`, the key of use only), in Ehsan's Google account (26 Oct 2026). */
    const val MIRROR = ""

    /** What [ChatNode.setFeeds] takes: both feeds, the admin lists and the relay. */
    @JvmStatic
    fun config(): JSONObject {
        val feeds = JSONArray()
            .put(JSONObject().put("id", CHANNEL).put("broadcast", true).put("name", "official-channel").put("owner", OWNER))
            .put(JSONObject().put("id", GROUP).put("broadcast", false).put("name", "official-group").put("owner", OWNER))
        return JSONObject().put("feeds", feeds).put("seed", JSONArray(SEED)).put("mirror", MIRROR)
    }
}
