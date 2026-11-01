package com.mlmvpn.scanner.engines.tgproxy

import android.content.Context
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.data.SecureStore
import com.mlmvpn.scanner.engines.cloud.WorkerRoute
import com.mlmvpn.scanner.models.CloudAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The app's one [TgProxyManager], wired to the real things: the Cloudflare accounts the user already
 * added (their credentials stay in [CloudManager]; nothing here copies them), the Keystore for the
 * proxy secrets, the app's own route to Cloudflare, and the relay script from the assets.
 */
object TgProxyEngine {

    @Volatile private var manager: TgProxyManager? = null

    fun get(context: Context): TgProxyManager = manager ?: synchronized(this) {
        manager ?: build(context.applicationContext).also {
            manager = it
            it.recover()
        }
    }

    private fun build(app: Context): TgProxyManager {
        val route = OkHttpClient.Builder()
            .dns(WorkerRoute.dns())
            .protocols(WorkerRoute.HTTP1)
            .socketFactory(WorkerRoute.cfSocketFactory())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        // The two self-test routes: the app's own, and the phone's ordinary network -- the one
        // Telegram itself will use. Short timeouts: a test that needs longer has already failed.
        val viaApp = route.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        val direct = OkHttpClient.Builder()
            .protocols(WorkerRoute.HTTP1)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val cloud = CloudManager(app)
        val source: () -> List<CloudAccount> = { cloud.accountsFlow.value }
        return TgProxyManager(
            store = TgStore(app.filesDir),
            api = TgCfApi(route),
            prober = TgProbe(viaApp, direct),
            accounts = source,
            code = { app.assets.open(ASSET).bufferedReader().use { it.readText() } },
            sealer = object : TgSealer {
                override fun seal(plain: String): String? = SecureStore.seal(plain)
                override fun open(sealed: String): String? = SecureStore.open(sealed)
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            createSubdomain = { account -> cloud.createSubdomain(account).ok },
        )
    }

    const val ASSET = "tgproxy_worker.js"
}
