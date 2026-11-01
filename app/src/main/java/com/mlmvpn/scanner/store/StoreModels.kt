package com.mlmvpn.scanner.store

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

// =================================================================================================
// «ام‌ال‌ام استور» for Android — the same store the Windows app has, rebuilt for a phone.
//
// Four kinds of thing are listed, and they are updated in genuinely different ways:
//
//   برنامه    MLM VPN itself, through the updater it already had (update/UpdateChecker.kt).
//   هسته‌ها   the native engines. A downloaded engine is run from the store's own folder, never
//             from nativeLibraryDir (which only the package installer can write) — see
//             StoreEngines.kt for how that is possible on Android 10+ and what it cannot cover.
//   ورکرها    the panels the app deploys to the user's OWN Cloudflare account. The store keeps the
//             code the app will deploy next (StoreFiles) AND replaces the code of the ones already
//             deployed, keeping every binding, secret and database (StoreWorkers.kt).
//   داده‌ها   files the engines read: Xray's routing data, the Iran profiles, and so on.
//
// Where a newer version comes from, per item ([StoreSource]):
//   * the DEVELOPER's own GitHub — a release asset or a file on a branch pinned to its commit;
//   * the signed channel (StoreChannel.kt) — our own builds, and anything we tested first;
//   * the app itself — what Android does not allow to be swapped at runtime.
// =================================================================================================

enum class StoreKind { APP, ENGINE, WORKER, DATA }

/** How an item's tile is drawn: finished artwork, or a white glyph on a tinted squircle. */
data class StoreIcon(
    val glyph: ImageVector,
    val tint: Color,
    val imageRes: Int? = null,
    /** Second tint for the gradient; the tile blends [tint] into this top to bottom. */
    val tint2: Color? = null,
)

/** Where newer versions of an item come from. */
sealed class StoreSource {

    /**
     * A release on the developer's GitHub.
     *
     * @param assetFor the asset this device needs, by ABI (null = none published for it).
     * @param extract path inside the archive -> file name the store installs it as.
     * @param versionInContent read the version from the file itself rather than from the tag —
     *   BPB's tag and its `panelVersion` agree today, but the file is what gets deployed.
     */
    data class GithubRelease(
        val repo: String,
        val assetFor: (abi: String) -> String?,
        val format: String = "raw",
        val extract: Map<String, String> = emptyMap(),
        val tagToVersion: (String) -> String = { it.removePrefix("v").removePrefix("V") },
        val versionInContent: Regex? = null,
    ) : StoreSource()

    /**
     * One file on a branch of the developer's repo — for projects that publish no releases.
     *
     * Whatever the branch points at is read ONCE, and the download is pinned to that commit, so
     * the bytes checked and the bytes installed are the same bytes.
     */
    data class GithubFile(
        val repo: String,
        val branch: String,
        val path: String,
        val versionInContent: Regex? = null,
    ) : StoreSource()

    /**
     * Several files on a branch of a repo that publishes no releases, taken together at ONE
     * commit (the newest that touched any of them) and turned into the item's data file by
     * [DataSpec.build] -- @patterniha's Serverless-for-Iran: two `.jsonc` → «کانفیگ‌های ایران».
     * Each file is also fetched through jsDelivr when raw.githubusercontent.com is filtered.
     */
    data class GithubFiles(
        val repo: String,
        val branch: String,
        val paths: List<String>,
        val versionInContent: Regex? = null,
    ) : StoreSource()

    /** Our own builds: only what the signed channel offers. */
    object Channel : StoreSource()

    /**
     * A developer that publishes a SIGNED release manifest rather than GitHub releases -- Geph.
     *
     * The manifest (`metadata.yaml`, minisign-signed with the key the developer's own client
     * hard-codes) names the build for [track] and its sha256. The build is the developer's own app
     * package; [extract] takes the engine out of it for one ABI, or says there is none.
     */
    data class SignedManifest(
        val mirrors: List<String>,
        val minisignKey: String,
        val track: String,
        val extract: (abi: String) -> Map<String, String>?,
    ) : StoreSource()

    /** Cannot be replaced at runtime on Android; arrives with a new version of the app. */
    object WithApp : StoreSource()

    /** The app itself. */
    object App : StoreSource()
}

/** A native engine: what is installed, how it is started, and how its version is asked. */
data class EngineSpec(
    /** Files the item installs, as they are named in jniLibs (`libaether.so`). */
    val libs: List<String>,
    /** EXEC: started as a process. JNI: loaded into the app with System.load. */
    val mode: Mode,
    val probeArgs: List<String> = emptyList(),
    val probeRe: Regex? = null,
    /** What this build of the app ships, when it cannot be asked (JNI libraries). */
    val shipped: String? = null,
    /**
     * Names the app drives this engine by -- environment variables, config fields, the log line it
     * waits for. A new version missing any of them would install, start, pass the version probe
     * and then break a feature on the next connect, so it is refused before it is used and the
     * working copy stays. Checked in the first file of [libs].
     */
    val requires: List<String> = emptyList(),
) {
    enum class Mode { EXEC, JNI }
}

/** A worker the app deploys. */
data class WorkerSpec(
    /** The asset the app reads when it deploys this worker. */
    val asset: String,
    /** Recognises this worker in code downloaded from an account. */
    val detect: (String) -> Boolean,
    /** Its version, read from its own code; null when the code carries none. */
    val versionOf: (String) -> String?,
    /** Markers new code must still contain, or the app could no longer drive it. */
    val mustContain: List<String> = emptyList(),
    val update: Update = Update.CONTENT,
    /** BPB: carry the per-account settings statement across to the new code. */
    val bpbPrefix: Boolean = false,
    val stripBom: Boolean = false,
    /**
     * For a worker whose deploy writes values into the code (SpiderPanel's token and domains):
     * the new code with the deployed copy's values carried across. Throws when they cannot be.
     */
    val inject: ((target: String, deployed: String) -> String)? = null,
    /** The code with those deploy-time values put back as placeholders, so the bytes can be compared. */
    val normalize: ((String) -> String)? = null,
) {
    /** What the store compares when a worker carries no version: the normalised bytes. */
    fun hashOf(code: String): String = StoreNet.sha256((normalize?.invoke(code) ?: code).removePrefix("﻿"))

    enum class Update {
        /** Replace the code only. Bindings, secrets, KV and D1 stay exactly as they are. */
        CONTENT,
        /** Config Studio: its own deployer, which runs the migrations and refuses to go back. */
        STUDIO,
    }
}

/** A data file an engine reads. */
data class DataSpec(
    val file: String,
    /** OVERLAY: a copy the app reads in place of the asset. FILES_DIR: the live file in filesDir. */
    val target: Target,
    val minBytes: Long = 16,
    val json: Boolean = false,
    /** The version the app ships, so an equal upstream version is not offered as an update. */
    val shipped: String? = null,
    /**
     * Turns the downloaded files (by their path in the repo) into this data file's text. Throws
     * when it cannot -- the store then installs nothing and keeps what works.
     */
    val build: ((android.content.Context, Map<String, String>) -> String)? = null,
) {
    enum class Target { OVERLAY, FILES_DIR }
}

data class StoreItem(
    val id: String,
    val kind: StoreKind,
    val titleFa: String,
    val titleEn: String,
    val subtitleFa: String,
    val subtitleEn: String,
    val developer: String,
    val repo: String?,
    val source: StoreSource,
    val icon: StoreIcon,
    val aboutFa: String = "",
    val aboutEn: String = "",
    val engine: EngineSpec? = null,
    val worker: WorkerSpec? = null,
    val data: DataSpec? = null,
)

/** One downloadable file of a candidate version. */
data class StoreArtifact(
    val name: String,
    val urls: List<String>,
    val sha256: String?,
    val size: Long,
    /** raw | tar.gz | zip */
    val format: String,
    /** path inside the archive -> installed file name. Empty for raw: the file is [name]. */
    val extract: Map<String, String> = emptyMap(),
)

/** A version the store could install. */
data class StoreCandidate(
    val version: String,
    val notes: String,
    val released: String,
    /** developer | channel */
    val from: String,
    val artifacts: List<StoreArtifact>,
    val url: String? = null,
)

/** What the store knows about one item right now. */
data class StoreRow(
    val item: StoreItem,
    /** What is in use: the store's copy if there is one, else what the app shipped. */
    val version: String?,
    val shipped: String?,
    /** True when the version in use came from the store. */
    val fromStore: Boolean,
    val installedAt: Long,
    val canRollback: Boolean,
    val candidate: StoreCandidate?,
    val state: State,
    val error: String? = null,
    val checkedAt: Long = 0L,
    /** Workers: every deployed copy on the user's accounts. */
    val deployments: List<Deployment> = emptyList(),
    /**
     * Engines built into the app: the newest version on the developer's own GitHub. It cannot be
     * installed from here -- it arrives with the next app version -- but it is shown, so the user
     * sees where the app stands against the developer.
     */
    val upstream: String? = null,
) {
    enum class State { UPDATE, CURRENT, UNKNOWN, WITH_APP, UNCHECKED }

    /** A deployed worker on one Cloudflare account. */
    data class Deployment(
        val accountId: String,
        val accountName: String,
        val script: String,
        val mainModule: String,
        val version: String?,
        val behind: Boolean,
    )

    val hasUpdate: Boolean get() = state == State.UPDATE || deployments.any { it.behind }
}

/** A long operation the screen watches. */
data class StoreJob(
    val id: String,
    val phase: String,
    /** 0..1, or null while the length is unknown. */
    val progress: Float?,
    val running: Boolean,
    val error: String? = null,
    val done: String? = null,
    /** Stopped by the user with the downloaded part kept: [StoreManager.resume] continues it. */
    val paused: Boolean = false,
    /** Bytes downloaded so far and in all (0 when unknown), and the current speed. */
    val bytes: Long = 0,
    val total: Long = 0,
    val speedBps: Long = 0,
)

/** One line of «بروزرسانی‌های اخیر». */
data class StoreHistory(
    val id: String,
    val title: String,
    val from: String?,
    val to: String,
    val at: Long,
    val notes: String,
)
