package com.mlmvpn.scanner.engines.mlm

import com.google.gson.annotations.SerializedName

// Models for /api/users
data class MlmUsersResponse(
    val users: List<MlmUser>,
    val serverTime: Long
)

data class MlmUser(
    val id: Int,
    val username: String,
    val uuid: String,
    @SerializedName("limit_gb") val limitGb: Float?,
    @SerializedName("daily_limit_gb") val dailyLimitGb: Float?,
    @SerializedName("daily_used_gb") val dailyUsedGb: Float?,
    @SerializedName("expiry_days") val expiryDays: Int?,
    val ips: String?,
    @SerializedName("connection_type") val connectionType: String?,
    val tls: String?,
    val port: String?,
    @SerializedName("used_gb") val usedGb: Float?,
    @SerializedName("is_active") val isActive: Int?,
    @SerializedName("last_active") val lastActive: Long?,
    @SerializedName("is_online") val isOnline: Int?, // Enriched by worker
    @SerializedName("created_at") val createdAt: String?,
    val fingerprint: String?,
    @SerializedName("proxy_ip") val proxyIp: String?,
    @SerializedName("daily_reset_at") val dailyResetAt: Long?
)

data class MlmCreateUserRequest(
    val username: String,
    @SerializedName("limit_gb") val limitGb: Float?,
    @SerializedName("daily_limit_gb") val dailyLimitGb: Float?,
    @SerializedName("expiry_days") val expiryDays: Int?,
    val ips: String? = null,
    val tls: String = "tls",
    val port: String = "443",
    val fingerprint: String? = "chrome",
    @SerializedName("proxy_ip") val proxyIp: String? = null
)

data class MlmUpdateUserRequest(
    @SerializedName("limit_gb") val limitGb: Float?,
    @SerializedName("daily_limit_gb") val dailyLimitGb: Float?,
    @SerializedName("expiry_days") val expiryDays: Int?,
    val ips: String?,
    val tls: String?,
    val port: String?,
    val fingerprint: String?,
    @SerializedName("proxy_ip") val proxyIp: String?
)

data class MlmToggleUserRequest(
    @SerializedName("toggle_only") val toggleOnly: Boolean = true
)

// Models for /api/proxy-ip
data class MlmProxySettings(
    @SerializedName("proxy_ip") val proxyIp: String,
    val iata: String,
    @SerializedName("frag_len") val fragLen: String,
    @SerializedName("frag_int") val fragInt: String
)

data class MlmProxySettingsRequest(
    @SerializedName("proxy_ip") val proxyIp: String?,
    val iata: String?,
    @SerializedName("frag_len") val fragLen: String?,
    @SerializedName("frag_int") val fragInt: String?
)

// General Response Models
data class MlmSuccessResponse(
    val success: Boolean,
    val error: String?
)

data class MlmLoginRequest(
    val password: String
)
