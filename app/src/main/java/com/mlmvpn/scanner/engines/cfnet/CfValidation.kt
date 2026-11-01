package com.mlmvpn.scanner.engines.cfnet

import org.json.JSONObject

/** Stage success predicates. Bodies are inspected in memory and never persisted. */
object CfValidation {
    fun api(status: Int, body: String): Boolean = status in 200..299 &&
        runCatching { JSONObject(body).optBoolean("success") }.getOrDefault(false)
    fun account(status: Int, body: String, bearer: Boolean): Boolean = api(status, body) &&
        (!bearer || runCatching { JSONObject(body).optJSONObject("result")?.optString("status") == "active" }.getOrDefault(false))
    fun accounts(status: Int, body: String): Boolean = api(status, body) && runCatching {
        JSONObject(body).optJSONArray("result")?.length()?.let { it > 0 } == true
    }.getOrDefault(false)
    fun identity(status: Int, body: String): Boolean = status in 200..299 && runCatching {
        val j = JSONObject(body)
        val peer = j.optJSONObject("config")?.optJSONArray("peers")?.optJSONObject(0)?.optString("public_key").orEmpty()
        j.optString("id").isNotBlank() && j.optString("token").isNotBlank() &&
            peer.matches(Regex("[A-Za-z0-9+/]{43}="))
    }.getOrDefault(false)
    fun nonce(status: Int, body: String, expected: String): Boolean =
        status == 200 && expected.isNotBlank() && body == expected
    fun transfer(status204: Int, downloaded: Long, httpsOk: Boolean, acknowledgedUpload: Long): Boolean =
        status204 == 204 && downloaded == 65_536L && httpsOk && acknowledgedUpload == 16_384L
}
