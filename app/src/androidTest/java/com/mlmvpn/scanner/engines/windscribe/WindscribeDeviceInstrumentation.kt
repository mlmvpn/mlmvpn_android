package com.mlmvpn.scanner.engines.windscribe

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import com.mlmvpn.scanner.openvpn.OpenVpnVault
import com.mlmvpn.scanner.openvpn.OpenVpnRuntime
import com.mlmvpn.scanner.openvpn.OpenVpnService
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.utils.GameSessionOwner
import com.wsnet.lib.WSNet
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Opt-in on-device tests using isolated storage; never reads or changes real Windscribe accounts. */
class WindscribeDeviceInstrumentation : Instrumentation() {
    private lateinit var arguments: Bundle
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); this.arguments = arguments ?: Bundle(); start() }
    override fun onStart() {
        if (arguments.getString("live") == "true") { liveRun(); return }
        val report = JSONObject()
        val directory = File(targetContext.noBackupFilesDir, "windscribe-smoke-${UUID.randomUUID()}")
        val isolated = object : ContextWrapper(targetContext) {
            override fun getNoBackupFilesDir(): File = directory.apply { mkdirs() }
        }
        try {
            val vault = OpenVpnVault(isolated)
            val secret = "device-test-session-${UUID.randomUUID()}"
            vault.write("roundtrip", secret)
            check(vault.read("roundtrip") == secret)
            val ciphertext = File(directory, "openvpn/roundtrip").readBytes()
            check(!ciphertext.toString(Charsets.ISO_8859_1).contains(secret))
            report.put("keystoreRoundtrip", "PASS").put("plaintextAbsent", "PASS")
            val repository = WsRepository(isolated)
            fun account(id: String) = JSONObject().put("user_id", id).put("username", "test-$id")
                .put("session_auth_hash", secret).put("traffic_max", "10000").put("traffic_used", "3000")
                .put("email_status", 1).put("is_premium", 0).put("status", 1)
            repository.login(account("test-a"))
            repository.login(account("test-b"))
            check(repository.data.value.accounts.size == 2)
            repository.selectAccount("test-a")
            check(repository.data.value.activeId == "test-a")
            check(repository.data.value.active?.remaining == 7000L)
            val method = WsMethod("server-1", WsProtocol.WIREGUARD, 443, true)
            repository.remember("test-a", "network-a", method)
            check(repository.preferred("test-a", "network-a") == method)
            check(repository.preferred("test-a", "network-b") == null)
            check(repository.preferred("test-b", "network-a") == null)
            repository.invalidate("test-a")
            check(repository.data.value.active?.sessionValid == false)
            check(runCatching { repository.token("test-a") }.exceptionOrNull() is WsException)
            repository.remove("test-a")
            check(repository.data.value.activeId == null)
            val restored = WsRepository(isolated)
            check(restored.data.value.accounts.single().id == "test-b")
            check(restored.installationId() == WsRepository(isolated).installationId())
            val result = WsMeasurement(method, 2500, 91, 1_000_000, 3, 3, System.currentTimeMillis())
            restored.saveMeasurements("test-b", "network-a", listOf(result))
            check(WsRepository(isolated).measurements("test-b", "network-a") == listOf(result))
            check(restored.measurements("test-b", "network-b").isEmpty())
            report.put("accountsSwitchRemoveReload", "PASS").put("sessionExpiration", "PASS").put("networkMethodIsolation", "PASS")
            report.put("measurementReload", "PASS").put("stableInstallationId", "PASS")
            check(WsProxyClient.available(targetContext))
            check(WsProxyClient.nativeSmoke(targetContext))
            report.put("officialProxyJNIIsolatedProcess", "PASS")
            val cm = targetContext.getSystemService(ConnectivityManager::class.java)
            if (cm.allNetworks.none { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } &&
                !MyVpnService.isRunning && !OpenVpnRuntime.connection.value.active) {
                val owner = "windscribe-cancelled-${UUID.randomUUID()}"
                GameSessionOwner.register(owner); GameSessionOwner.release(owner)
                runOnMainSync {
                    targetContext.startService(Intent(targetContext, MyVpnService::class.java)
                        .putExtra(GameSessionOwner.EXTRA, owner).putExtra("NODE_ID", "windscribe_cancelled_probe"))
                    targetContext.startService(Intent(targetContext, OpenVpnService::class.java).setAction(OpenVpnService.CONNECT)
                        .putExtra(GameSessionOwner.EXTRA, owner).putExtra("profile", "windscribe_cancelled_probe").putExtra("account", OpenVpnRuntime.SELF))
                }
                runBlocking { delay(1000) }
                check(!MyVpnService.isRunning && !OpenVpnRuntime.connection.value.active)
                report.put("cancelledServiceStartsRejected", "PASS")
            } else report.put("cancelledServiceStartsRejected", "SKIP_EXISTING_VPN")
            val client = WsApi(isolated, restored)
            client.mode("device-smoke", true)
            check(WSNet.isValid())
            check(client.responseLoggingDisabled())
            report.put("wsnetNativeInit", "PASS").put("apiResponseLoggingDisabled", "PASS")
            if (arguments.getString("apiProbe") == "true") runBlocking {
                val result = runCatching { client.json("device-smoke") { api, cb -> api.session("invalid-device-test-session", "", "", 0, -1, cb) } }
                val error = result.exceptionOrNull() as? WsException
                report.put("realInvalidSession", if (error?.error == WsError.SESSION_EXPIRED) "PASS" else "UNAVAILABLE")
                report.put("apiProbeError", error?.error?.name)
            }
            val storeFile = File(directory, "openvpn/windscribe")
            val bytes = storeFile.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); storeFile.writeBytes(bytes)
            check(WsRepository(isolated).data.value.storageError)
            report.put("corruptVaultFailsClosed", "PASS")
            File(targetContext.noBackupFilesDir, "windscribe-device-smoke.json").writeText(report.toString())
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", report.toString()) })
        } catch (t: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Windscribe smoke failed: ${t.javaClass.simpleName}") })
        } finally {
            runCatching { if (WSNet.isValid()) WSNet.cleanup() }
            directory.deleteRecursively()
        }
    }
    /** Explicit opt-in regression using the user's selected local account, with no credential output. */
    private fun liveRun() = runBlocking {
        val report = JSONObject()
        val engine = WindscribeEngine
        var resultCode = Activity.RESULT_OK
        val snapshot = WsRepository(targetContext).data.value
        val active = snapshot.active
        report.put("accountPresent", active != null).put("sessionValid", active?.sessionValid == true)
            .put("cachedServers", snapshot.servers.size).put("advertisedProtocols", snapshot.ports.keys.map { it.code })
        try {
            if (active == null) error("NO_LOCAL_ACCOUNT")
            val cache = WsRepository(targetContext)
            report.put("wireguardConfigCached", cache.secret(active.id, "wg_init") != null)
                .put("openVpnConfigCached", cache.secret(active.id, "ovpn_template") != null)
            sendStatus(0, Bundle().apply { putString("stream", report.toString()) })
            engine.init(targetContext)
            withTimeout(8_000) { engine.data.first { it.active != null || it.storageError } }
            val protocol = arguments.getString("protocol")?.takeIf { it != "AUTO" }?.let(WsProtocol::valueOf)
            engine.selectProtocol(protocol)
            withTimeout(5_000) { while (engine.data.value.protocol != protocol || engine.busy.value) delay(100) }
            engine.connect(targetContext, searchAgain = arguments.getString("searchAgain") == "true")
            withTimeout(if (arguments.getString("searchAgain") == "true") 210_000 else 125_000) {
                while (!engine.busy.value && engine.connection.value.phase == WsPhase.DISCONNECTED) delay(100)
                var ticks = 0
                while (engine.busy.value) {
                    delay(1000)
                    if (++ticks % 5 == 0) {
                        val state = engine.connection.value
                        sendStatus(0, Bundle().apply { putString("stream", "phase=${state.phase} attempt=${state.attempt}/${state.total} protocol=${state.method?.protocol} port=${state.method?.port}") })
                    }
                }
            }
            val state = engine.connection.value
            report.put("phase", state.phase.name).put("error", state.error?.name)
                .put("protocol", state.method?.protocol?.code).put("port", state.method?.port).put("latencyMs", state.latencyMs)
            report.put("attempts", org.json.JSONArray(engine.results.value.map { m -> JSONObject()
                .put("protocol", m.method.protocol.code).put("port", m.method.port).put("padding", m.method.padding)
                .put("success", m.success).put("latencyMs", m.latencyMs).put("connectMs", m.connectMs) }))
            val native = OpenVpnRuntime.connection.value
            report.put("openVpnPhase", native.phase.name)
                .put("openVpnError", native.error?.takeIf { it.matches(Regex("[A-Z0-9_]+")) })
        } catch (t: Throwable) {
            report.put("testError", t.javaClass.simpleName)
            resultCode = Activity.RESULT_CANCELED
        } finally {
            engine.disconnect()
            kotlinx.coroutines.withTimeoutOrNull(15_000) { while(engine.busy.value || engine.connection.value.phase in setOf(WsPhase.CONNECTED, WsPhase.CONNECTING, WsPhase.DISCONNECTING)) delay(100) }
            engine.selectProtocol(snapshot.protocol)
        }
        File(targetContext.noBackupFilesDir, "windscribe-live-test.json").writeText(report.toString())
        finish(resultCode, Bundle().apply { putString("stream", report.toString()) })
    }
}
