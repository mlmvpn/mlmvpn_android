package com.mlmvpn.scanner.engines.game

import android.content.Context

// اطلاعات هر بازی
data class GameInfo(
    val id: String,
    val name: String,
    val packageName: String,
    val alternatePackages: List<String> = emptyList(),
    val iconEmoji: String,
    val servers: List<GameServer>,
    val defaultRegion: String = "ME",
    val category: String = "shooter"
)

// اطلاعات سرور هر بازی
data class GameServer(
    val region: String,
    val displayName: String,
    val testEndpoints: List<String>,
    val port: Int = 443
)

// نتیجه تست بوست
data class BoostResult(
    val mode: BoostMode,
    val pingMs: Long,
    val jitterMs: Long = 0,
    val nodeId: String? = null,
    val nodeName: String? = null,
    val nodeUri: String? = null,
    val details: String = ""
)

enum class BoostMode {
    DIRECT,      // Direct Boost — بدون تانل
    TUNNEL,      // Tunnel Turbo — از نودهای VLESS/Trojan
    WORKER,      // Worker Relay — از Cloudflare Workers
    WARP,        // Warp WireGuard — از Cloudflare WARP با پینگ پایین
    AUTO         // Auto Hybrid — خودکار بهترین
}

// وضعیت کلی Game Booster
enum class BoosterState {
    IDLE,           // آماده
    TESTING,        // در حال تست
    BOOSTED,        // بوست شده و فعال
    CONNECTING,     // در حال اتصال
    FAILED          // خطا
}

object GameDatabase {
    
    val games: List<GameInfo> = listOf(
        // ────────────────── Call of Duty Mobile ──────────────────
        GameInfo(
            id = "codm",
            name = "Call of Duty Mobile",
            packageName = "com.activision.callofduty.shooter",
            alternatePackages = listOf("com.garena.game.codm"),
            iconEmoji = "🔫",
            category = "shooter",
            servers = listOf(
                GameServer("ME", "خاورمیانه", listOf("prod.atviservices.com", "conntest.activision.com"), 443),
                GameServer("EU", "اروپا", listOf("eu.prod.atviservices.com", "conntest.activision.com"), 443)
            )
        ),
        
        // ────────────────── PUBG Mobile ──────────────────
        GameInfo(
            id = "pubg",
            name = "PUBG Mobile",
            packageName = "com.tencent.ig",
            alternatePackages = listOf("com.pubg.krmobile", "com.rekoo.pubgm", "com.pubg.imobile"),
            iconEmoji = "🪖",
            category = "battle_royale",
            servers = listOf(
                GameServer("ME", "خاورمیانه", listOf("midas-public-slb-sg.proxima-world.com", "gp-pjp-sg.publb.tencentmna.com"), 443),
                GameServer("EU", "اروپا", listOf("gp-pjp-eu.publb.tencentmna.com"), 443)
            )
        ),
        
        // ────────────────── Mobile Legends: Bang Bang ──────────────────
        GameInfo(
            id = "mlbb",
            name = "Mobile Legends",
            packageName = "com.mobile.legends",
            iconEmoji = "⚔️",
            category = "moba",
            servers = listOf(
                GameServer("ME", "خاورمیانه", listOf("moba.mobilelegends.com", "gateway.mobilelegends.com"), 443),
                GameServer("AS", "آسیا", listOf("as-gateway.mobilelegends.com"), 443)
            )
        ),
        
        // ────────────────── Free Fire ──────────────────
        GameInfo(
            id = "freefire",
            name = "Free Fire",
            packageName = "com.dts.freefireth",
            alternatePackages = listOf("com.dts.freefiremax"),
            iconEmoji = "🔥",
            category = "battle_royale",
            servers = listOf(
                GameServer("ME", "خاورمیانه", listOf("apigw-sea.garena.com", "sea-login.garena.com"), 443),
                GameServer("AS", "آسیا", listOf("api.garena.com"), 443)
            )
        ),
        
        // ────────────────── Clash Royale ──────────────────
        GameInfo(
            id = "clashroyale",
            name = "Clash Royale",
            packageName = "com.supercell.clashroyale",
            iconEmoji = "👑",
            category = "strategy",
            servers = listOf(
                GameServer("EU", "اروپا", listOf("game.clashroyaleapp.com", "game.supercell.com"), 9339)
            )
        ),
        
        // ────────────────── Clash of Clans ──────────────────
        GameInfo(
            id = "clashofclans",
            name = "Clash of Clans",
            packageName = "com.supercell.clashofclans",
            iconEmoji = "⚔️",
            category = "strategy",
            servers = listOf(
                GameServer("EU", "اروپا", listOf("game.clashofclans.com", "game.supercell.com"), 9339)
            )
        ),
        
        // ────────────────── Genshin Impact ──────────────────
        GameInfo(
            id = "genshin",
            name = "Genshin Impact",
            packageName = "com.miHoYo.GenshinImpact",
            iconEmoji = "🌟",
            category = "rpg",
            servers = listOf(
                GameServer("AS", "آسیا", listOf("dispatchosglobal.yuanshen.com", "osasiadispatch.yuanshen.com"), 443),
                GameServer("EU", "اروپا", listOf("oseurodispatch.yuanshen.com"), 443)
            )
        ),
        
        // ────────────────── Brawl Stars ──────────────────
        GameInfo(
            id = "brawlstars",
            name = "Brawl Stars",
            packageName = "com.supercell.brawlstars",
            iconEmoji = "💥",
            category = "shooter",
            servers = listOf(
                GameServer("EU", "اروپا", listOf("game.brawlstarsgame.com", "game.supercell.com"), 9339)
            )
        ),

        // ────────────────── Blood Strike ──────────────────
        GameInfo(
            id = "bloodstrike",
            name = "Blood Strike",
            packageName = "com.netease.newspike.na",
            alternatePackages = listOf("com.netease.newspike.gl"),
            iconEmoji = "🩸",
            category = "shooter",
            servers = listOf(
                GameServer("ME", "خاورمیانه", listOf("login-global.service.newspike.netease.com", "common-global.service.newspike.netease.com"), 443),
                GameServer("EU", "اروپا", listOf("login-global.service.newspike.netease.com"), 443)
            )
        )
    )

    // پیدا کردن بازی‌های نصب شده روی دستگاه
    fun getInstalledGames(context: Context): List<GameInfo> {
        val pm = context.packageManager
        return games.filter { game ->
            val allPackages = listOf(game.packageName) + game.alternatePackages
            allPackages.any { pkg ->
                try { pm.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
            }
        }
    }

    // همه بازی‌ها (نصب‌شده اول، بقیه بعد)
    fun getAllGamesSorted(context: Context): List<Pair<GameInfo, Boolean>> {
        val pm = context.packageManager
        return games.map { game ->
            val allPackages = listOf(game.packageName) + game.alternatePackages
            val isInstalled = allPackages.any { pkg ->
                try { pm.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
            }
            Pair(game, isInstalled)
        }.sortedByDescending { it.second } // نصب‌شده‌ها اول
    }
}
