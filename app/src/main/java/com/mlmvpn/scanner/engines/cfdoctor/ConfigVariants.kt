package com.mlmvpn.scanner.engines.cfdoctor

import org.json.JSONArray
import org.json.JSONObject

object ConfigVariants {
    fun apply(base: String,variant: String): String {
        val j=JSONObject(base); val outs=j.getJSONArray("outbounds")
        val proxy=(0 until outs.length()).map { outs.getJSONObject(it) }.first { it.optString("tag")=="proxy" }
        val stream=proxy.optJSONObject("streamSettings") ?: JSONObject().also { proxy.put("streamSettings",it) }
        val tls=stream.optJSONObject("tlsSettings") ?: JSONObject().also { stream.put("tlsSettings",it) }
        when {
            variant.startsWith("C3.") -> {
                val recipe=variant.substringAfter('.')
                fun frag(packets: String,lengths: List<String>,delays: List<String>,split: String)=JSONObject().put("type","fragment").put("settings",JSONObject()
                    .put("packets",packets).put("lengths",JSONArray(lengths)).put("delays",JSONArray(delays)).put("maxSplit",split))
                val masks=JSONArray()
                when(recipe) {
                    "F3" -> masks.put(frag("tlshello",listOf("5","1"),listOf("0"),"0"))
                    "F12" -> masks.put(frag("tlshello",listOf("50-100"),listOf("10-20"),"0"))
                    "F15" -> { masks.put(frag("tlshello",listOf("5","1"),listOf("0"),"0")); masks.put(frag("1-1",listOf("43","1"),listOf("1"),"522")) }
                    else -> throw IllegalArgumentException()
                }
                stream.put("finalmask",JSONObject().put("tcp",masks))
            }
            variant=="C7" -> { tls.remove("fingerprint"); tls.put("minVersion","1.2").put("maxVersion","1.2").put("alpn",JSONArray().put("http/1.1")) }
            variant=="C8" -> tls.put("minVersion","1.2").put("maxVersion","1.2").put("cipherSuites","TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256:TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256")
            variant.startsWith("C9.") -> {
                val resolver=when(variant.substringAfter('.')) { "udp" -> "udp://8.8.8.8"; "google" -> "https://8.8.8.8/dns-query"; "cloudflare" -> "https://1.1.1.1/dns-query"; else -> throw IllegalArgumentException() }
                tls.put("echConfigList",resolver).put("echForceQuery","full")
            }
            variant.startsWith("C2.") -> {
                val fp=variant.substringAfter('.'); require(fp in DoctorPlan().fingerprints)
                if(fp.isEmpty()) tls.remove("fingerprint") else tls.put("fingerprint",fp)
            }
            variant.startsWith("C10.") -> {
                val mode=variant.substringAfter('.'); require(mode in setOf("auto","packet-up","stream-up","stream-one"))
                stream.getJSONObject("xhttpSettings").put("mode",mode)
            }
        }
        j.put("log",JSONObject().put("loglevel","none"))
        return j.toString()
    }
}
