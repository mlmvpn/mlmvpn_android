package com.mlmvpn.scanner.mitm

import android.content.Context
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.engines.gst.GstLog
import com.mlmvpn.scanner.models.VpnNode

/**
 * Sets up the MITM domain-fronting profile: a server-less config from @patterniha's
 * MITM-DomainFronting project that reaches YouTube / Instagram / WhatsApp / Facebook / Reddit
 * and other Fastly-hosted sites **directly**, with no relay of any kind in the path.
 *
 * How it gets there: the config runs two local `tunnel` inbounds that terminate TLS using a
 * certificate we issue on-device, reads the plaintext SNI/Host, then re-establishes the real
 * connection to the true server under a *different*, unblocked SNI. Since the traffic never
 * leaves for a proxy, there is no server bandwidth to pay for and no per-user secret -- which
 * is why one identical config can ship to every user (contrast the cloud-panel profiles, which
 * are per-account). The only per-device part is the certificate, minted by [MitmCertManager].
 *
 * Known limitation, surfaced verbatim in the wizard UI: on non-rooted Android 7+, a
 * user-installed CA is honoured by Chromium-based browsers but *not* by ordinary apps. So this
 * profile opens the sites in a browser; the YouTube and Instagram apps stay blocked. That is a
 * platform rule, not something this implementation can work around.
 */
object MitmProfile {

    /** Its own folder in the connection tab, kept separate from the Iran defaults. */
    const val GROUP = "دامین‌فرانتینگ (بدون سرور)"

    const val NODE_ID = "mitm_domainfronting_v23"
    const val NODE_NAME = "دامین‌فرانتینگ v23 — بدون سرور"

    private const val ASSET = "mitm_domainfronting_v23.json"
    private const val CERT_PLACEHOLDER = "__MLM_CERT_PATH__"
    private const val KEY_PLACEHOLDER = "__MLM_KEY_PATH__"

    /** The config's `mixed` inbound is hardcoded to 10808 upstream and we ship it untouched. */
    const val REQUIRED_PORT = "10808"

    /**
     * Builds the runnable config: the shipped asset with the two certificate placeholders
     * replaced by absolute paths under `filesDir`.
     *
     * Absolute rather than relative on purpose -- Xray resolves a bare filename against its
     * asset location, and we would rather not depend on that lookup matching where we wrote
     * the files.
     */
    fun buildConfig(context: Context): String? = try {
        val raw = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        val withPaths = raw
            .replace(CERT_PLACEHOLDER, MitmCertManager.certFile(context).absolutePath)
            .replace(KEY_PLACEHOLDER, MitmCertManager.keyFile(context).absolutePath)
        if (withPaths.contains(CERT_PLACEHOLDER) || withPaths.contains(KEY_PLACEHOLDER)) {
            GstLog.e("MitmProfile", "placeholder substitution failed")
            null
        } else {
            withPaths
        }
    } catch (e: Exception) {
        GstLog.e("MitmProfile", "asset load failed: ${e.message}")
        null
    }

    fun isInstalled(context: Context): Boolean =
        NodeManager(context).nodes.any { it.id == NODE_ID }

    /**
     * The one-shot the wizard calls: mint the certificate if needed, force the local port to the
     * value the config expects, then create (or refresh) the node in its own group.
     *
     * Idempotent -- running it again refreshes the config in place instead of piling up copies.
     * Returns false only when the certificate could not be created or the asset is unreadable,
     * i.e. when there would be nothing usable to connect to.
     */
    fun setUp(context: Context): Boolean {
        if (!MitmCertManager.ensure(context)) return false
        val config = buildConfig(context) ?: return false

        // The upstream config listens on 10808 and we do not rewrite it, so a user who changed
        // the app's local port would otherwise get a silent bind failure.
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString("local_port", REQUIRED_PORT).apply()

        val manager = NodeManager(context)
        synchronized(manager.nodes) {
            manager.nodes.removeAll { it.id == NODE_ID }
            manager.nodes.add(
                0,
                VpnNode(
                    id = NODE_ID,
                    name = NODE_NAME,
                    uri = config,
                    type = "JSON",
                    engineType = "Manual",
                    groupTitle = GROUP
                )
            )
        }
        manager.saveNodes()
        GstLog.i("MitmProfile", "profile registered in group '$GROUP'")
        return true
    }

    /** Removes the node (and its folder, which disappears once empty). Certificate is kept. */
    fun remove(context: Context) {
        val manager = NodeManager(context)
        synchronized(manager.nodes) { manager.nodes.removeAll { it.id == NODE_ID } }
        manager.saveNodes()
    }

    /** True when everything is in place and a connection attempt makes sense. */
    fun isReady(context: Context): Boolean =
        MitmCertManager.exists(context) &&
            MitmCertManager.isTrusted(context) &&
            isInstalled(context)
}
