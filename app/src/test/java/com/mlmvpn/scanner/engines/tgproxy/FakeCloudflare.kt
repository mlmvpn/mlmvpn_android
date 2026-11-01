package com.mlmvpn.scanner.engines.tgproxy

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** A tiny HTTP/1.1 server on localhost that plays Cloudflare's API for the tests. */
class FakeCloudflare : AutoCloseable {

    class Req(val method: String, val path: String, val query: String, val headers: Map<String, String>, val body: ByteArray) {
        val text: String get() = String(body, Charsets.ISO_8859_1)
    }

    class Res(val status: Int, val body: String = """{"success":true,"result":{}}""", val headers: Map<String, String> = emptyMap())

    val requests = CopyOnWriteArrayList<Req>()

    /** What answers each request. Replaced per test. */
    @Volatile var handler: (Req) -> Res = { Res(200) }

    /** Held while handling, so a test can freeze a call in flight. */
    @Volatile var gate: (() -> Unit)? = null

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val base = "http://127.0.0.1:${server.localPort}/client/v4"

    init {
        thread(isDaemon = true, name = "fake-cloudflare") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { runCatching { serve(socket) } }
            }
        }
    }

    private fun serve(socket: Socket) = socket.use { s ->
        s.soTimeout = 15_000
        val input = BufferedInputStream(s.getInputStream())
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0d0a0d0a) break
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val (method, target) = lines[0].split(" ")
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val req = Req(method, target.substringBefore('?'), target.substringAfter('?', ""), headers, body)
        requests += req
        gate?.invoke()
        val res = handler(req)
        val payload = res.body.toByteArray()
        val out = s.getOutputStream()
        val extra = res.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
        out.write("HTTP/1.1 ${res.status} X\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n$extra\r\n".toByteArray())
        out.write(payload)
        out.flush()
    }

    override fun close() {
        runCatching { server.close() }
    }
}

/** A stateful Cloudflare account: Workers scripts, Pages projects, sites and DNS, with the ownership marker. */
class FakeAccount(val id: String = "ACC1") {

    class Script(val owner: String?, val secret: String?, var enabled: Boolean = false)
    class Project(val owner: String?, val secret: String?, val host: String, var deployments: Int = 0)
    class Zone(val id: String, val name: String, val status: String = "active", val ns: List<String> = listOf("ada.ns.cloudflare.com", "bob.ns.cloudflare.com"))
    class Record(val id: String, val name: String, val type: String, val content: String, val comment: String, val proxied: Boolean = false)

    val scripts = LinkedHashMap<String, Script>()
    val projects = LinkedHashMap<String, Project>()
    val zones = ArrayList<Zone>()
    val records = ArrayList<Record>()
    val workerDomains = LinkedHashMap<String, Pair<String, String>>()
    val pageDomains = LinkedHashMap<String, MutableMap<String, String>>()
    var subdomain: String? = "mysub"
    var deployStatus = "success"
    var pageDomainStatus = "active"
    val deletes = ArrayList<String>()
    private var counter = 0

    private fun ok(result: Any?): FakeCloudflare.Res = FakeCloudflare.Res(200, JSONObject().put("success", true).put("result", result ?: JSONObject()).toString())
    private fun fail(status: Int, code: Int): FakeCloudflare.Res =
        FakeCloudflare.Res(status, JSONObject().put("success", false).put("errors", JSONArray().put(JSONObject().put("code", code).put("message", "boom"))).toString())

    private val w = Regex("^/client/v4/accounts/$id/workers/scripts/([^/]+)(/settings|/subdomain)?$")
    private val p = Regex("^/client/v4/accounts/$id/pages/projects/([^/]+)(/deployments(?:/([^/]+))?)?$")
    private val pd = Regex("^/client/v4/accounts/$id/pages/projects/([^/]+)/domains(?:/([^/]+))?$")
    private val wd = Regex("^/client/v4/accounts/$id/workers/domains(?:/([^/]+))?$")
    private val dns = Regex("^/client/v4/zones/([^/]+)/dns_records(?:/([^/]+))?$")

    fun handle(r: FakeCloudflare.Req): FakeCloudflare.Res {
        val path = r.path
        if (path == "/client/v4/accounts/$id/workers/subdomain") {
            return ok(JSONObject().put("subdomain", subdomain ?: ""))
        }
        if (path == "/client/v4/accounts/$id/workers/scripts" && r.method == "GET") {
            return ok(JSONArray().apply { scripts.keys.forEach { put(JSONObject().put("id", it)) } })
        }
        w.matchEntire(path)?.let { m ->
            val name = m.groupValues[1]
            when (m.groupValues[2]) {
                "" -> when (r.method) {
                    "PUT" -> {
                        val meta = metadata(r.text)
                        scripts[name] = Script(binding(meta, "MLM_TGP_ID"), binding(meta, "PROXY_SECRET"), scripts[name]?.enabled ?: false)
                        return ok(JSONObject())
                    }
                    "DELETE" -> {
                        deletes += name
                        return if (scripts.remove(name) == null) fail(404, 10007) else ok(JSONObject())
                    }
                    else -> Unit
                }
                "/subdomain" -> {
                    val s = scripts[name] ?: return fail(404, 10007)
                    s.enabled = true
                    return ok(JSONObject().put("enabled", true))
                }
                "/settings" -> {
                    val s = scripts[name] ?: return fail(404, 10007)
                    val bindings = JSONArray().put(JSONObject().put("type", "secret_text").put("name", "PROXY_SECRET"))
                    s.owner?.let { bindings.put(JSONObject().put("type", "plain_text").put("name", "MLM_TGP_ID").put("text", it)) }
                    return ok(JSONObject().put("bindings", bindings))
                }
                else -> Unit
            }
        }
        if (path == "/client/v4/accounts/$id/pages/projects") {
            if (r.method == "GET") return ok(JSONArray().apply { projects.keys.forEach { put(JSONObject().put("name", it)) } })
            if (r.method == "POST") {
                val body = JSONObject(String(r.body))
                val name = body.getString("name")
                if (projects.containsKey(name)) return fail(400, 8000002)
                val env = body.getJSONObject("deployment_configs").getJSONObject("production").getJSONObject("env_vars")
                projects[name] = Project(env.optJSONObject("MLM_TGP_ID")?.optString("value"), env.optJSONObject("PROXY_SECRET")?.optString("value"), "$name.pages.dev")
                return ok(JSONObject().put("name", name))
            }
        }
        pd.matchEntire(path)?.let { m ->
            val project = m.groupValues[1]
            if (!projects.containsKey(project)) return fail(404, 8000007)
            val domains = pageDomains.getOrPut(project) { LinkedHashMap() }
            val host = m.groupValues[2]
            when {
                host.isEmpty() && r.method == "POST" -> {
                    val name = JSONObject(String(r.body)).getString("name")
                    if (domains.containsKey(name)) return fail(409, 8000018)
                    domains[name] = pageDomainStatus
                    return ok(JSONObject().put("name", name).put("status", pageDomainStatus))
                }
                host.isNotEmpty() && r.method == "GET" -> {
                    val status = domains[host] ?: return fail(404, 8000018)
                    return ok(JSONObject().put("name", host).put("status", status))
                }
                host.isNotEmpty() && r.method == "DELETE" -> {
                    deletes += "pagesdomain:$host"
                    return if (domains.remove(host) == null) fail(404, 8000018) else ok(JSONObject())
                }
                else -> Unit
            }
        }
        p.matchEntire(path)?.let { m ->
            val name = m.groupValues[1]
            val project = projects[name] ?: return fail(404, 8000007)
            when {
                m.groupValues[2].isEmpty() -> when (r.method) {
                    "GET" -> {
                        val env = JSONObject().put("PROXY_SECRET", JSONObject().put("type", "secret_text"))
                        project.owner?.let { env.put("MLM_TGP_ID", JSONObject().put("type", "plain_text").put("value", it)) }
                        return ok(JSONObject().put("name", name).put("subdomain", project.host)
                            .put("deployment_configs", JSONObject().put("production", JSONObject().put("env_vars", env))))
                    }
                    "DELETE" -> {
                        deletes += name
                        projects.remove(name)
                        return ok(JSONObject())
                    }
                    else -> Unit
                }
                m.groupValues[3].isEmpty() && r.method == "POST" -> {
                    project.deployments++
                    return ok(JSONObject().put("id", "dep-${project.deployments}"))
                }
                r.method == "GET" -> {
                    val stage = JSONObject().put("name", "deploy").put("status", if (deployStatus == "success") "success" else "failure")
                    return ok(JSONObject().put("id", m.groupValues[3]).put("latest_stage", stage)
                        .put("stages", JSONArray().put(stage)))
                }
                else -> Unit
            }
        }
        wd.matchEntire(path)?.let { m ->
            val domainId = m.groupValues[1]
            if (domainId.isEmpty() && r.method == "PUT") {
                val body = JSONObject(String(r.body))
                val newId = "wd-${++counter}"
                workerDomains[newId] = body.getString("hostname") to body.getString("service")
                return ok(JSONObject().put("id", newId).put("hostname", body.getString("hostname")))
            }
            if (domainId.isNotEmpty() && r.method == "DELETE") {
                deletes += "workersdomain:$domainId"
                return if (workerDomains.remove(domainId) == null) fail(404, 10007) else ok(JSONObject())
            }
        }
        if (path == "/client/v4/zones" && r.method == "GET") {
            return ok(JSONArray().apply {
                zones.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("status", it.status).put("name_servers", JSONArray(it.ns))) }
            })
        }
        dns.matchEntire(path)?.let { m ->
            val recordId = m.groupValues[2]
            fun json(rec: Record) = JSONObject().put("id", rec.id).put("name", rec.name).put("type", rec.type).put("content", rec.content).put("comment", rec.comment).put("proxied", rec.proxied)
            when {
                recordId.isEmpty() && r.method == "GET" -> {
                    val name = r.query.split('&').firstOrNull { it.startsWith("name=") }?.removePrefix("name=")
                    return ok(JSONArray().apply { records.filter { name == null || it.name == name }.forEach { put(json(it)) } })
                }
                recordId.isEmpty() && r.method == "POST" -> {
                    val body = JSONObject(String(r.body))
                    val rec = Record("rec-${++counter}", body.getString("name"), body.getString("type"), body.getString("content"), body.optString("comment"), body.optBoolean("proxied"))
                    records += rec
                    return ok(json(rec))
                }
                recordId.isNotEmpty() && r.method == "GET" -> {
                    val rec = records.firstOrNull { it.id == recordId } ?: return fail(404, 81044)
                    return ok(json(rec))
                }
                recordId.isNotEmpty() && r.method == "DELETE" -> {
                    deletes += "record:$recordId"
                    return if (records.removeAll { it.id == recordId }) ok(JSONObject()) else fail(404, 81044)
                }
                else -> Unit
            }
        }
        return fail(404, 7003)
    }

    /** The JSON of the `metadata` part of a multipart upload. */
    private fun metadata(body: String): JSONObject {
        val start = body.indexOf("name=\"metadata\"")
        val json = body.indexOf("{", body.indexOf("\r\n\r\n", start))
        val end = body.indexOf("\r\n--", json)
        return JSONObject(body.substring(json, end))
    }

    private fun binding(meta: JSONObject, name: String): String? {
        val arr = meta.optJSONArray("bindings") ?: return null
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            if (b.optString("name") == name) return b.optString("text")
        }
        return null
    }
}
