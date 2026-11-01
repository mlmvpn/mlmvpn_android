package com.mlmvpn.scanner.engines.scout

/** Probe-only configuration; never reads or writes the user's selected Tor mode. */
object ScoutTorPolicy {
    fun listener(line: String, transport: String): String? {
        val fields = line.trim().split(Regex("\\s+"))
        return fields.takeIf { it.size == 4 && it[0] == "CMETHOD" && it[1] == transport && it[2] == "socks5" }
            ?.get(3)?.takeIf(::localEndpoint)
    }

    private fun localEndpoint(value: String): Boolean = value.startsWith("127.0.0.1:") &&
        (value.substringAfterLast(':').toIntOrNull() ?: 0) in 1..65535

    fun config(directory: String, port: Int, bridges: List<String>, transport: String?, listener: String?, upstream: String?): String {
        require(port in 1..65535)
        require(directory.none { it == '\n' || it == '\r' })
        require(bridges.all { it.isNotBlank() && '\n' !in it && '\r' !in it })
        require(transport == null || transport in setOf("obfs4", "meek_lite", "webtunnel", "snowflake"))
        require(transport == null || (bridges.isNotEmpty() && listener != null && localEndpoint(listener)))
        require(upstream == null || localEndpoint(upstream))
        return buildString {
            appendLine("SocksPort 127.0.0.1:$port")
            appendLine("DNSPort 0")
            appendLine("ControlPort 0")
            appendLine("DataDirectory \"${directory.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
            appendLine("ClientOnly 1")
            appendLine("SocksPolicy accept 127.0.0.0/8")
            appendLine("SocksPolicy reject *")
            appendLine("Log notice stdout")
            appendLine("SafeLogging 1")
            appendLine("AvoidDiskWrites 1")
            appendLine("ClientBootstrapConsensusAuthorityDownloadInitialDelay 0")
            appendLine("LearnCircuitBuildTimeout 0")
            appendLine("CircuitBuildTimeout 120")
            appendLine("KeepalivePeriod 30")
            appendLine("ClientUseIPv4 1")
            appendLine("ClientUseIPv6 1")
            if (upstream != null && transport == null) appendLine("Socks5Proxy $upstream")
            if (bridges.isNotEmpty()) {
                appendLine("UseBridges 1")
                if (transport != null) appendLine("ClientTransportPlugin $transport socks5 $listener")
                bridges.forEach { appendLine("Bridge $it") }
            }
        }
    }
}
