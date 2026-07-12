package com.mlmvpn.scanner.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.utils.AmneziaWgConfigGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ── Trial Engine (singleton) ──
object UaeTrialEngine {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private const val API_BASE = "http://194.50.233.133:3000"

    // AmneziaWG obfuscation params. The server sends its own values so the client always
    // matches the server interface (change awg0.conf and clients follow automatically).
    data class AwgParams(
        val jc: Int = 4, val jmin: Int = 40, val jmax: Int = 70,
        val s1: Int = 0, val s2: Int = 0,
        val h1: Int = 1, val h2: Int = 2, val h3: Int = 3, val h4: Int = 4
    )

    data class TrialConfig(
        val privateKey: String,
        val address: String,
        val serverPubkey: String,
        val endpoint: String,
        val mtu: Int,
        val dns: String,
        val gameSubnets: List<String>,
        val tickIntervalMs: Long,
        val awg: AwgParams = AwgParams()
    )

    private fun parseAwg(wg: JSONObject): AwgParams {
        val a = wg.optJSONObject("awg") ?: return AwgParams()
        return AwgParams(
            jc = a.optInt("jc", 4), jmin = a.optInt("jmin", 40), jmax = a.optInt("jmax", 70),
            s1 = a.optInt("s1", 0), s2 = a.optInt("s2", 0),
            h1 = a.optInt("h1", 1), h2 = a.optInt("h2", 2), h3 = a.optInt("h3", 3), h4 = a.optInt("h4", 4)
        )
    }

    enum class TrialState { IDLE, LOADING, ACTIVE, EXPIRED, ERROR }

    private val _state = MutableStateFlow(TrialState.IDLE)
    val state = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _remainingSeconds = MutableStateFlow(0L)
    val remainingSeconds = _remainingSeconds.asStateFlow()

    private val _totalBytes = MutableStateFlow(0L)
    val totalBytes = _totalBytes.asStateFlow()

    private val _usedBytes = MutableStateFlow(0L)
    val usedBytes = _usedBytes.asStateFlow()

    private val _config = MutableStateFlow<TrialConfig?>(null)
    val config = _config.asStateFlow()

    private var tickJob: Job? = null
    private var localUiTimerJob: Job? = null
    private var routingWatchJob: Job? = null

    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    suspend fun requestTrial(context: Context, nickname: String): Result<TrialConfig> = withContext(Dispatchers.IO) {
        _state.value = TrialState.LOADING
        _error.value = null

        try {
            val deviceId = getDeviceId(context)
            val body = JSONObject()
                .put("device_id", deviceId)
                .put("nickname", nickname)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$API_BASE/api/trial")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            val json = JSONObject(responseBody)

            if (!response.isSuccessful) {
                val errorMsg = when (json.optString("error")) {
                    "trial_used" -> "شما قبلاً از تست رایگان استفاده کرده‌اید"
                    "no_ips" -> "سرور پر است. لطفاً بعداً تلاش کنید"
                    else -> json.optString("message", "خطای سرور")
                }
                _error.value = errorMsg
                _state.value = TrialState.ERROR
                return@withContext Result.failure(Exception(errorMsg))
            }


            // Parse new config (works for both 'created' and 'active' status)
            val wg = json.getJSONObject("wg")
            val subnetsArr = wg.getJSONArray("game_subnets")
            val subnets = mutableListOf<String>()
            for (i in 0 until subnetsArr.length()) subnets.add(subnetsArr.getString(i))
            // SECURITY: log only the subnets, never the raw wg block (it holds private_key + endpoint IP).
            android.util.Log.d("UaeTrial", "Server game_subnets=$subnets (count=${subnets.size})")

            val trialConfig = TrialConfig(
                privateKey = wg.getString("private_key"),
                address = wg.getString("address"),
                serverPubkey = wg.getString("server_pubkey"),
                endpoint = wg.getString("endpoint"),
                mtu = wg.optInt("mtu", 1280),
                dns = wg.optString("dns", "8.8.8.8"),
                gameSubnets = subnets,
                tickIntervalMs = json.optLong("tick_interval", 30) * 1000L,
                awg = parseAwg(wg)
            )

            _config.value = trialConfig
            _remainingSeconds.value = json.optLong("remaining_seconds")
            _totalBytes.value = json.optLong("total_bytes")
            _usedBytes.value = json.optLong("used_bytes")
            _state.value = TrialState.ACTIVE

            // Persist the FULL config (not just device_id) so the trial can be started from anywhere
            // -- notably the Game tab -- and survives an app restart, without having to reopen the
            // WireGuard tab first. Validation still happens server-side via device_id.
            context.getSharedPreferences("wg_trial", Context.MODE_PRIVATE)
                .edit()
                .putString("device_id", deviceId)
                .apply()
            persistConfig(context, trialConfig)

            Result.success(trialConfig)
        } catch (e: Exception) {
            // SECURITY: never surface e.message here -- for a ConnectException it embeds the server
            // IP:port ("Failed to connect to /1.2.3.4:3000"), which must never be shown to the user.
            android.util.Log.d("UaeTrial", "createTrial network error (${e.javaClass.simpleName})")
            _error.value = "اتصال به سرور برقرار نشد. اینترنت خود را بررسی کنید و دوباره تلاش کنید."
            _state.value = TrialState.ERROR
            Result.failure(e)
        }
    }

    /** Force-closes the trial tunnel. Safe to call multiple times / when already stopped. */
    fun forceStopTrial(context: Context, reason: String) {
        android.util.Log.d("UaeTrial", "forceStopTrial: $reason")
        _remainingSeconds.value = 0
        _state.value = TrialState.EXPIRED
        appliedGameSubnets = null
        val intent = Intent(context, MyVpnService::class.java).apply { action = "STOP" }
        context.startService(intent)
    }

    private val isTrialConnected get() = MyVpnService.connectedNodeId == "game_uae_trial"

    /** @return true if the server was reached and answered, false on network failure. */
    private const val TRIAL_PREFS = "wg_trial"

    /** Serialize the full trial config to prefs so it's available from any tab / after a restart. */
    private fun persistConfig(context: Context, cfg: TrialConfig) {
        try {
            val o = JSONObject().apply {
                put("private_key", cfg.privateKey)
                put("address", cfg.address)
                put("server_pubkey", cfg.serverPubkey)
                put("endpoint", cfg.endpoint)
                put("mtu", cfg.mtu)
                put("dns", cfg.dns)
                put("game_subnets", org.json.JSONArray(cfg.gameSubnets))
                put("tick_interval_ms", cfg.tickIntervalMs)
                put("awg", JSONObject().apply {
                    put("jc", cfg.awg.jc); put("jmin", cfg.awg.jmin); put("jmax", cfg.awg.jmax)
                    put("s1", cfg.awg.s1); put("s2", cfg.awg.s2)
                    put("h1", cfg.awg.h1); put("h2", cfg.awg.h2); put("h3", cfg.awg.h3); put("h4", cfg.awg.h4)
                })
            }
            context.getSharedPreferences(TRIAL_PREFS, Context.MODE_PRIVATE)
                .edit().putString("config_json", o.toString()).apply()
        } catch (_: Exception) {}
    }

    /** Rebuild [_config] from prefs if it's currently null (e.g. Game tab on a fresh app start). */
    fun restoreConfigIfNeeded(context: Context) {
        if (_config.value != null) return
        try {
            val raw = context.getSharedPreferences(TRIAL_PREFS, Context.MODE_PRIVATE)
                .getString("config_json", null) ?: return
            val o = JSONObject(raw)
            val subs = mutableListOf<String>()
            o.optJSONArray("game_subnets")?.let { for (i in 0 until it.length()) subs.add(it.getString(i)) }
            val a = o.optJSONObject("awg") ?: JSONObject()
            _config.value = TrialConfig(
                privateKey = o.getString("private_key"),
                address = o.getString("address"),
                serverPubkey = o.getString("server_pubkey"),
                endpoint = o.getString("endpoint"),
                mtu = o.optInt("mtu", 1280),
                dns = o.optString("dns", "8.8.8.8"),
                gameSubnets = subs,
                tickIntervalMs = o.optLong("tick_interval_ms", 30000L),
                awg = AwgParams(
                    jc = a.optInt("jc", 4), jmin = a.optInt("jmin", 40), jmax = a.optInt("jmax", 70),
                    s1 = a.optInt("s1", 0), s2 = a.optInt("s2", 0),
                    h1 = a.optInt("h1", 1), h2 = a.optInt("h2", 2), h3 = a.optInt("h3", 3), h4 = a.optInt("h4", 4)
                )
            )
        } catch (_: Exception) {}
    }

    /** Drop the persisted config once the trial is truly over so a stale one is never reused. */
    private fun clearPersistedConfig(context: Context) {
        try {
            context.getSharedPreferences(TRIAL_PREFS, Context.MODE_PRIVATE)
                .edit().remove("config_json").apply()
        } catch (_: Exception) {}
    }

    suspend fun checkStatus(context: Context): Boolean = withContext(Dispatchers.IO) {
        // Make sure a previously-obtained trial config is loaded even before the network answers,
        // so the Game tab can start WireGuard without first visiting the WireGuard tab.
        restoreConfigIfNeeded(context)
        try {
            val deviceId = getDeviceId(context)
            val request = Request.Builder()
                .url("$API_BASE/api/trial/status?device_id=$deviceId")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            val status = json.optString("status")

            when (status) {
                "active" -> {
                    val total = json.optLong("total_bytes")
                    val used = json.optLong("used_bytes")
                    _remainingSeconds.value = json.optLong("remaining_seconds")
                    _totalBytes.value = total
                    _usedBytes.value = used
                    _state.value = TrialState.ACTIVE
                    if (json.has("wg")) {
                        val wg = json.getJSONObject("wg")
                        val subnetsArr = wg.getJSONArray("game_subnets")
                        val subnets = mutableListOf<String>()
                        for (i in 0 until subnetsArr.length()) subnets.add(subnetsArr.getString(i))

                        _config.value = TrialConfig(
                            privateKey = wg.getString("private_key"),
                            address = wg.getString("address"),
                            serverPubkey = wg.getString("server_pubkey"),
                            endpoint = wg.getString("endpoint"),
                            mtu = wg.optInt("mtu", 1280),
                            dns = wg.optString("dns", "8.8.8.8"),
                            gameSubnets = subnets,
                            tickIntervalMs = json.optLong("tick_interval", 30) * 1000L,
                            awg = parseAwg(wg)
                        )
                        _config.value?.let { persistConfig(context, it) }
                    }
                    // Data cap reached (server also enforces, but close instantly).
                    if (total > 0 && used >= total && isTrialConnected) {
                        forceStopTrial(context, "data cap reached ($used/$total)")
                    }
                }
                // Server says the trial is over (time OR data) → tear the tunnel down now.
                "expired" -> {
                    clearPersistedConfig(context)
                    if (isTrialConnected) forceStopTrial(context, "server status=expired")
                    else _state.value = TrialState.EXPIRED
                }
                "none" -> {
                    clearPersistedConfig(context)
                    if (isTrialConnected) forceStopTrial(context, "server status=none")
                    else _state.value = TrialState.IDLE
                }
            }
            true
        } catch (e: Exception) {
            false // network failure (e.g. full-tunnel died after expiry)
        }
    }

    // This is called periodically ONLY when the VPN is active and specifically running the trial config
    suspend fun sendTick(context: Context, deltaSeconds: Int = 30) = withContext(Dispatchers.IO) {
        try {
            val deviceId = getDeviceId(context)
            val body = JSONObject().apply {
                put("device_id", deviceId)
                put("delta_seconds", deltaSeconds)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$API_BASE/api/trial/tick")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            
            val status = json.optString("status")
            val err = json.optString("error")
            if (status == "expired" || err == "no_active_trial") {
                // Time expired, OR the server's background job already removed the peer
                // (e.g. data cap reached → tick returns 404 no_active_trial).
                if (isTrialConnected) forceStopTrial(context, "tick: status=$status err=$err")
            } else if (status == "active") {
                _remainingSeconds.value = json.optLong("remaining_seconds")
                _totalBytes.value = json.optLong("total_bytes")
                _usedBytes.value = json.optLong("used_bytes")
            }
            Unit
        } catch (e: Exception) {
            // Ignore temporary network errors during tick
        }
    }

    private var lastTickTime = 0L

    // The game_subnets currently baked into the running tunnel. Used to detect a mid-session
    // routing switch (admin toggles full/game routing) and re-apply it live.
    @Volatile var appliedGameSubnets: List<String>? = null

    fun startUsageTracker(context: Context) {
        tickJob?.cancel()
        localUiTimerJob?.cancel()

        // UI countdown. Also the LOCAL time-expiry guard: if the whole phone is tunneled and
        // the trial ends, the internet dies and server polls can't get through — so when the
        // local countdown hits 0 we tear the tunnel down ourselves.
        localUiTimerJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                delay(1000)
                _remainingSeconds.value = (_remainingSeconds.value - 1).coerceAtLeast(0)
                if (_remainingSeconds.value <= 0L) {
                    if (isTrialConnected) forceStopTrial(context, "local countdown reached 0")
                    break
                }
            }
        }

        // Server tick
        lastTickTime = System.currentTimeMillis()
        val interval = _config.value?.tickIntervalMs ?: 30000L
        tickJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && _remainingSeconds.value > 0) {
                delay(interval)
                val now = System.currentTimeMillis()
                val delta = ((now - lastTickTime) / 1000).toInt()
                lastTickTime = now
                if (delta > 0) sendTick(context, delta)
            }
        }

        // Fast routing watcher (~2s): applies an admin full/game switch almost instantly,
        // without any user action, even mid-session. Kept separate from the 30s usage tick.
        routingWatchJob = CoroutineScope(Dispatchers.IO).launch {
            var consecutiveFailures = 0
            while (isActive && _remainingSeconds.value > 0) {
                delay(2000)
                val reached = checkStatus(context)
                if (!reached) {
                    consecutiveFailures++
                    // If we can't reach the server for ~30s while supposedly connected as the
                    // trial, the tunnel has most likely died (e.g. full-tunnel data cap hit).
                    // Fail safe: tear it down so the user isn't left with a dead VPN + key icon.
                    if (consecutiveFailures >= 15 && isTrialConnected) {
                        forceStopTrial(context, "server unreachable for ~30s — assuming tunnel died")
                        break
                    }
                    continue
                }
                consecutiveFailures = 0

                val latest = _config.value?.gameSubnets
                val applied = appliedGameSubnets
                if (latest != null && applied != null && latest.toSet() != applied.toSet() && _remainingSeconds.value > 0) {
                    android.util.Log.d("UaeTrial", "Routing changed live: $applied -> $latest — re-applying tunnel")
                    _config.value?.let { startGameVpn(context, it) }
                }
            }
        }
    }

    fun stopUsageTracker(context: Context) {
        tickJob?.cancel()
        localUiTimerJob?.cancel()
        routingWatchJob?.cancel()
        
        if (lastTickTime > 0) {
            val now = System.currentTimeMillis()
            val delta = ((now - lastTickTime) / 1000).toInt()
            if (delta > 0) {
                CoroutineScope(Dispatchers.IO).launch { sendTick(context, delta) }
            }
        }
        lastTickTime = 0L
    }

    fun reset() {
        tickJob?.cancel()
        localUiTimerJob?.cancel()
        _state.value = TrialState.IDLE
        _config.value = null
        _remainingSeconds.value = 0
        _totalBytes.value = 0L
        _usedBytes.value = 0L
        _error.value = null
    }
}

// ── UI ──
@Composable
fun WireguardTab() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val trialState by UaeTrialEngine.state.collectAsState()
    val remainingSeconds by UaeTrialEngine.remainingSeconds.collectAsState()
    val totalBytes by UaeTrialEngine.totalBytes.collectAsState()
    val usedBytes by UaeTrialEngine.usedBytes.collectAsState()
    val trialConfig by UaeTrialEngine.config.collectAsState()
    val trialError by UaeTrialEngine.error.collectAsState()
    
    val phase by MyVpnService.connectionPhaseFlow.collectAsState()
    val connectedNodeId by MyVpnService.connectedNodeIdFlow.collectAsState()

    // It's only the trial VPN if it's connected AND the config ID matches our trial config
    val isTrialActiveConfig = connectedNodeId == "game_uae_trial"
    val connected = phase == MyVpnService.Phase.CONNECTED && isTrialActiveConfig
    val connecting = phase == MyVpnService.Phase.CONNECTING && isTrialActiveConfig

    var nickname by remember { mutableStateOf("") }

    // NOTE: the usage tracker (countdown / server tick / auto-stop) is now driven centrally in
    // AppScreen from the real VPN state, so it runs regardless of which tab started the trial or is
    // open. It must NOT also be managed here, or the two observers would fight (start/stop races).

    // Check status on first load
    LaunchedEffect(Unit) {
        UaeTrialEngine.checkStatus(context)
    }

    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            trialConfig?.let { startGameVpn(context, it) }
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.BgDark)
            // bottom = 16 margin + 64 for the app's bottom nav bar (it overlays content,
            // it's not a Scaffold bottomBar) so the pinned button sits just above it.
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 100.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Text(
                "🎮 بوست بازی",
                color = AppColors.TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                "کاهش پینگ بازی‌ها با سرور اختصاصی امارات",
                color = AppColors.TextMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // Status Card
            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (trialState) {
                        UaeTrialEngine.TrialState.IDLE -> {
                            Icon(
                                Icons.Rounded.SportsEsports,
                                contentDescription = null,
                                tint = AppColors.Primary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("تست رایگان ۱ ساعته", color = AppColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "این تست دقیقا یک ساعت زمان استفاده به شما می‌دهد و قابل تمدید نیست. لطفاً بعد از استفاده نظرات خود را در یوتیوب با ما به اشتراک بگذارید.",
                                color = AppColors.TextMuted,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 20.sp
                            )
                            Spacer(Modifier.height(16.dp))
                            
                            OutlinedTextField(
                                value = nickname,
                                onValueChange = { nickname = it },
                                label = { Text("نام شما در بازی (اختیاری)", color = AppColors.TextMuted) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AppColors.Primary,
                                    unfocusedBorderColor = AppColors.BorderDark,
                                    focusedTextColor = AppColors.TextPrimary,
                                    unfocusedTextColor = AppColors.TextPrimary
                                ),
                                singleLine = true
                            )
                        }

                        UaeTrialEngine.TrialState.LOADING -> {
                            CircularProgressIndicator(color = AppColors.Primary, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("در حال دریافت کانفیگ...", color = AppColors.TextMuted, fontSize = 14.sp)
                        }

                        UaeTrialEngine.TrialState.ACTIVE -> {
                            // Timer display
                            val h = (remainingSeconds / 3600).toInt()
                            val m = ((remainingSeconds % 3600) / 60).toInt()
                            val s = (remainingSeconds % 60).toInt()
                            val timeStr = if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)

                            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                            val scale by infiniteTransition.animateFloat(
                                initialValue = 0.8f,
                                targetValue = 1.2f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1000, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "scale"
                            )

                            if (connected) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .scale(scale)
                                        .clip(CircleShape)
                                        .background(AppColors.Primary)
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            Icon(
                                Icons.Rounded.Timer,
                                contentDescription = null,
                                tint = if (m < 5 && h == 0) AppColors.Error else AppColors.Primary,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                timeStr,
                                color = if (m < 5 && h == 0) AppColors.Error else AppColors.TextPrimary,
                                fontSize = 40.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                            
                            if (totalBytes > 0) {
                                Spacer(Modifier.height(8.dp))
                                val usedMb = String.format("%.1f", usedBytes / 1048576f)
                                val totalMb = String.format("%.1f", totalBytes / 1048576f)
                                val progress = (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                val pbColor = if (progress > 0.9f) AppColors.Error else AppColors.Primary
                                
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(0.8f)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("حجم باقیمانده: ${String.format("%.1f", (totalBytes - usedBytes) / 1048576f)} MB", color = AppColors.TextMuted, fontSize = 11.sp)
                                        Text("$usedMb / $totalMb MB", color = AppColors.TextMuted, fontSize = 11.sp)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = progress,
                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                        color = pbColor,
                                        trackColor = AppColors.SurfaceVariant
                                    )
                                }
                            }
                            
                            if (connected) {
                                Text(
                                    "زمان استفاده در حال کسر شدن است...",
                                    color = AppColors.Primary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Text(
                                    "زمان استفاده متوقف است (فقط زمان روشن بودن محاسبه می‌شود)",
                                    color = AppColors.TextMuted,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        UaeTrialEngine.TrialState.EXPIRED -> {
                            Text("⏰", fontSize = 48.sp)
                            Spacer(Modifier.height(12.dp))
                            Text("زمان تست شما به پایان رسید", color = AppColors.Error, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text("لطفاً نظرات خود را در یوتیوب بنویسید", color = AppColors.TextMuted, fontSize = 12.sp)
                        }

                        UaeTrialEngine.TrialState.ERROR -> {
                            Text("⚠️", fontSize = 48.sp)
                            Spacer(Modifier.height(12.dp))
                            Text(trialError ?: "خطای ناشناخته", color = AppColors.Error, fontSize = 14.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { UaeTrialEngine.reset() }) {
                                Text("تلاش مجدد")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Features
            if (trialState == UaeTrialEngine.TrialState.IDLE) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        FeatureRow("🚀", "پینگ بهینه", "سرور اختصاصی امارات")
                        FeatureRow("🎯", "فقط بازی", "اینترنت عادی شما تغییر نمی‌کند")
                        FeatureRow("⏳", "۱ ساعت خالص", "زمان فقط در زمان اتصال کسر می‌شود")
                        FeatureRow("📱", "ضد تقلب", "ثبت در سرور بر اساس سخت‌افزار")
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Main Action Button
        when {
            trialState == UaeTrialEngine.TrialState.IDLE -> {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val result = UaeTrialEngine.requestTrial(context, nickname)
                            result.onSuccess { config ->
                                val prep = VpnService.prepare(context)
                                if (prep != null) {
                                    vpnPrepareLauncher.launch(prep)
                                } else {
                                    startGameVpn(context, config)
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Primary,
                        contentColor = AppColors.BgDark
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.SportsEsports, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("شروع تست رایگان", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            trialState == UaeTrialEngine.TrialState.ACTIVE && !connected && !connecting -> {
                Button(
                    onClick = {
                        trialConfig?.let { config ->
                            val prep = VpnService.prepare(context)
                            if (prep != null) {
                                vpnPrepareLauncher.launch(prep)
                            } else {
                                startGameVpn(context, config)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Primary,
                        contentColor = AppColors.BgDark
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("روشن کردن و شروع بازی", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            connecting -> {
                Button(
                    onClick = { },
                    enabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.SurfaceVariant,
                        contentColor = AppColors.PrimaryBlue,
                        disabledContainerColor = AppColors.SurfaceVariant,
                        disabledContentColor = AppColors.PrimaryBlue
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    CircularProgressIndicator(color = AppColors.PrimaryBlue, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("در حال اتصال...", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            connected -> {
                Button(
                    onClick = {
                        val intent = Intent(context, MyVpnService::class.java).apply { action = "STOP" }
                        context.startService(intent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Error.copy(alpha = 0.18f),
                        contentColor = AppColors.Error
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("توقف VPN (تایمر قطع می‌شود)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            trialState == UaeTrialEngine.TrialState.EXPIRED -> {
                // Do nothing
            }
        }

    }
}

@Composable
private fun FeatureRow(emoji: String, title: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(emoji, fontSize = 20.sp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = AppColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = AppColors.TextMuted, fontSize = 11.sp)
        }
    }
}

private fun startGameVpn(context: Context, config: UaeTrialEngine.TrialConfig) {
    val vpnPrefs = context.getSharedPreferences("vpn_routing_prefs", Context.MODE_PRIVATE)
    val userMtu = vpnPrefs.getInt("vpn_mtu", -1)
    val finalMtu = if (userMtu > 0) userMtu else config.mtu

    val jsonConfig = com.mlmvpn.scanner.utils.AmneziaWgConfigGenerator.generateAmneziaWgConfig(
        privateKey = config.privateKey,
        address = config.address,
        serverPubkey = config.serverPubkey,
        endpoint = config.endpoint,
        mtu = finalMtu,
        dns = config.dns,
        gameSubnets = config.gameSubnets,
        // AmneziaWG obfuscation params come from the server so client ⇄ server always match.
        jc = config.awg.jc, jmin = config.awg.jmin, jmax = config.awg.jmax,
        s1 = config.awg.s1, s2 = config.awg.s2,
        h1 = config.awg.h1, h2 = config.awg.h2, h3 = config.awg.h3, h4 = config.awg.h4
    )

    // Remember what routing is now live, so the tick loop can detect a server-side switch.
    UaeTrialEngine.appliedGameSubnets = config.gameSubnets
    val fullTunnel = config.gameSubnets.isEmpty() || config.gameSubnets.contains("0.0.0.0/0")
    android.util.Log.d("UaeTrial", "startGameVpn: fullTunnel=$fullTunnel gameSubnets=${config.gameSubnets}")

    val intent = Intent(context, MyVpnService::class.java).apply {
        putExtra("NODE_URI", jsonConfig)
        putExtra("NODE_ID", "game_uae_trial") // Critical for tracking!
    }
    context.startService(intent)
}

