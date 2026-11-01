package com.mlmvpn.scanner.engines.arena

import android.content.Context
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.data.studio.domain.SubscriptionPolicy
import com.mlmvpn.scanner.models.CloudAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * MLM's configs for the arena, from the arena's own user -- never one of the operator's people.
 *
 * On an account Config Studio manages, the user is made through Config Studio's API and its
 * `/s/` subscription is read; that subscription opens with information entries (volume and time
 * left) at 127.0.0.1, which are not servers and are dropped here. An older, non-Studio MLM panel
 * goes through its own API.
 */
object ArenaStudio {

    suspend fun links(context: Context, account: CloudAccount): List<String> = withContext(Dispatchers.IO) {
        val api = StudioStore.get(context).apiFor(account)
        val existing = (api.listUsers(query = ArenaPanels.ARENA_USER, limit = 10) as? StudioResult.Ok)?.value?.items
            ?.firstOrNull { it.username == ArenaPanels.ARENA_USER }
        val user = existing ?: when (val made = api.createUser(ArenaPanels.ARENA_USER, SubscriptionPolicy(), note = "MLMVPN «میدان کانفیگ»")) {
            is StudioResult.Ok -> made.value
            is StudioResult.Err -> error("Config Studio: ${made.error.code}")
        }
        // A Studio user is born with NO configs: its own «کاربر تازه» screen makes them right
        // after, through LocationConfigs. The first race made the arena user and read an empty
        // subscription (on the phone, 2026-09-27: MLM «کانفیگی نداد»). So an arena user with no
        // config gets the screen's defaults -- VLESS and Trojan over WebSocket, direct.
        val http = com.mlmvpn.scanner.data.studio.api.StudioHttpApi(context, account)
        val configs = (http.listConfigs(user.id) as? StudioResult.Ok)?.value.orEmpty()
        if (configs.isEmpty()) {
            val made = com.mlmvpn.scanner.data.studio.config.LocationConfigs.create(
                http, user, com.mlmvpn.scanner.data.studio.config.LocationConfigs.DEFAULT, template = null,
                countries = emptyList(), includeDirect = true, xhttpCarriesCountry = false,
            )
            android.util.Log.i("Arena", "MLM: arena user had no config; made ${made.made.size}, failed ${made.failed.size}")
            if (made.made.isEmpty()) error("Config Studio: ${made.firstError?.code ?: "no config made"}")
        }
        val sub = when (val s = api.getSubscription(user.id)) {
            is StudioResult.Ok -> s.value
            is StudioResult.Err -> error("Config Studio: ${s.error.code}")
        }
        android.util.Log.i("Arena", "MLM via Config Studio: user ${user.id}, subscription ${sub.url.substringBefore("/s/")}/s/…")
        fetchList(context, sub.url).ifEmpty { error("Config Studio: " + com.mlmvpn.scanner.store.tr("اشتراک کاربر مسابقه کانفیگی نداشت", "the arena user's subscription had no config")) }
    }

    suspend fun legacyLinks(context: Context, account: CloudAccount): List<String> = withContext(Dispatchers.IO) {
        val mlm = com.mlmvpn.scanner.engines.mlm.MlmApiManager(context)
        val has = mlm.getUsers(account)?.any { it.username == ArenaPanels.ARENA_USER } == true
        if (!has) mlm.createUser(account, com.mlmvpn.scanner.engines.mlm.MlmCreateUserRequest(ArenaPanels.ARENA_USER, null, null, null))
        android.util.Log.i("Arena", "MLM via the old panel: arena user existed=$has")
        mlm.getUserConfigs(account, ArenaPanels.ARENA_USER).ifEmpty { error(com.mlmvpn.scanner.store.tr("پنل MLM برای کاربر مسابقه کانفیگی نداد", "The MLM panel gave the arena user no config")) }
    }

    /** A base64 (or plain) link list, without the 127.0.0.1 information entries. */
    fun fetchList(context: Context, url: String): List<String> {
        com.mlmvpn.scanner.update.UpdateNet.client(context, 15, 30, directFirst = true).newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val body = r.body?.string().orEmpty().trim()
            val text = if (body.contains("://")) body else runCatching { String(android.util.Base64.decode(body, android.util.Base64.DEFAULT)) }.getOrDefault(body)
            android.util.Log.i("Arena", "subscription: ${body.length} bytes, ${text.lines().count { it.contains("://") }} links, type ${r.header("Content-Type")}")
            return text.split("\n").map { it.trim() }
                .filter { (it.startsWith("vless://") || it.startsWith("trojan://")) && !it.contains("@127.0.0.1:") }
        }
    }
}
