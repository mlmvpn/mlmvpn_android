package com.mlmvpn.scanner.engines.scout

/** A stale session is a setup problem, not evidence that the network blocks its method. */
object ScoutSessionPolicy {
    fun probeConfig(outbound: org.json.JSONObject): String = org.json.JSONObject()
        .put("log", org.json.JSONObject().put("loglevel", "none"))
        .put("outbounds", org.json.JSONArray().put(org.json.JSONObject(outbound.toString()).put("tag", "proxy")))
        .toString()

    fun usable(status: String, expiresAt: Long, now: Long): Boolean =
        status in setOf("READY", "ACTIVE", "EXPIRING_SOON") && (expiresAt == 0L || expiresAt > now)

    fun acceptHandoff(currentRevision: Int, incomingRevision: Int, phase: String): Boolean =
        phase == "ready" && incomingRevision >= currentRevision
}
