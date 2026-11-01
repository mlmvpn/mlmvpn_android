package com.mlmvpn.scanner.engines.scout

/** Facts needed to keep WARP probes independent of remembered routes and direct connections. */
object ScoutWarpPolicy {
    fun verified(code: Int, body: String): Boolean {
        val values = body.lineSequence().map { it.trim() }.filter { it.startsWith("warp=") }.toList()
        return code == 200 && values.size == 1 && values.single() in setOf("warp=on", "warp=plus")
    }

    fun cleanParent(parent: Map<String, String>): Map<String, String> = parent.filterKeys {
        !it.startsWith("AETHER_", ignoreCase = true) && !it.endsWith("_PROXY", ignoreCase = true)
    }

    fun pinFamily(env: Map<String, String>, family: Fam): Map<String, String> {
        require(family == Fam.V4 || family == Fam.V6)
        return env + ("AETHER_IP" to if (family == Fam.V4) "v4" else "v6")
    }
}
