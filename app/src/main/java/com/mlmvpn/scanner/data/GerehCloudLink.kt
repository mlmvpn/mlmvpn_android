package com.mlmvpn.scanner.data

import android.content.Context
import com.mlmvpn.scanner.models.CloudAccount
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * «گره»'s combined relay (SPEC 19.13) on the user's own Cloudflare account, the one added in the
 * cloud section: the chat asks through [org.mlmvpn.chat.bridge.GerehCloud], which this fills in the
 * chat's own `:chat` process. How the relay's Worker is put up (the Durable Object, its migration,
 * the workers.dev address) is the bridge's, the same in every app that carries Gereh (both teams,
 * issue 7, 28 Oct 2026); this gives only what is MLMVPN's: the accounts, the road to Cloudflare's
 * API, and a Worker name that says nothing. None of the app's start-up runs in `:chat`, so the
 * little the Cloudflare calls need of it (the language, the edges, the API route) is bound at the
 * first request.
 */
object GerehCloudLink {
    @Volatile
    private var prepared = false

    // the same route the rest of the app takes to Cloudflare's API: a proven edge, with the
    // ClientHello split past the name filter on networks that cut api.cloudflare.com by name
    private val client by lazy {
        OkHttpClient.Builder()
            .dns(com.mlmvpn.scanner.engines.cloud.WorkerRoute.dns())
            .socketFactory(com.mlmvpn.scanner.engines.cloud.WorkerRoute.cfSocketFactory())
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    }

    fun bind(context: Context) {
        val app = context.applicationContext
        org.mlmvpn.chat.bridge.GerehCloud.set(object : org.mlmvpn.chat.bridge.GerehCloud.Hook {
            override fun accounts(): List<String> {
                prepare(app)
                return accountsOf(app).map { a -> a.name.ifBlank { a.email } }
            }

            override fun call(index: Int, method: String, path: String, contentType: String?, body: ByteArray?): org.mlmvpn.chat.bridge.GerehCloud.Reply {
                prepare(app)
                val a = accountsOf(app).getOrNull(index) ?: throw IllegalStateException(fa("حساب کلادفلر پیدا نشد.", "No Cloudflare account."))
                val request = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/${a.accountId}$path")
                    .headers(CloudAuth.headers(a))
                    .method(method, body?.toRequestBody(contentType?.toMediaTypeOrNull()))
                    .build()
                return client.newCall(request).execute().use { r -> org.mlmvpn.chat.bridge.GerehCloud.Reply(r.code, r.body?.string() ?: "") }
            }

            override fun newWorkerName(): String = com.mlmvpn.scanner.utils.AntiDpi.generateSafeWorkerName()
        })
    }

    /** The parts of the app's start-up (MlmApp) the Cloudflare calls use; each only keeps the context. */
    @Synchronized
    private fun prepare(app: Context) {
        if (prepared) return
        com.mlmvpn.scanner.utils.AppLocaleManager.init(app)
        CfEdge.init(app)
        com.mlmvpn.scanner.engines.cfnet.CfNetManager.bind(app)
        com.mlmvpn.scanner.engines.cloud.CfTunnel.bind(app)
        prepared = true
    }

    private fun accountsOf(context: Context): List<CloudAccount> = CloudManager(context).loadedAccounts()

    private fun fa(fa: String, en: String) = com.mlmvpn.scanner.store.tr(fa, en)
}
