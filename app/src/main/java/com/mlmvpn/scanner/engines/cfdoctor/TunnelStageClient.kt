package com.mlmvpn.scanner.engines.cfdoctor

import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** This is ephemeral input, deliberately never serialized by the report writer. */
data class TunnelConfig(val protocol: String,val address: String,val port: Int,val credential: String,
    val transport: String,val host: String,val path: String,val sni: String=host,val tls: Boolean=true)

class TunnelStageClient(private val env: NetEnv,private val budget: DataBudget) {
    suspend fun test(config: TunnelConfig,timeout: Int=20000,recipe: SplitRecipe=SplitRecipe()): Evidence {
        var phase="dns"; val begin=env.now(); val metrics=linkedMapOf<String,Long>("terminal" to 1)
        return try { ProbeSession.run(env,budget,timeout,ProbeSession.stagePool) { session ->
            val ip=env.resolve(config.address).firstOrNull()?.hostAddress ?: throw java.net.UnknownHostException()
            if(CfBogon.isBogon(ip)) return@run Evidence("ST3","baseline","dns_bogon",numbers=metrics)
            var active: java.net.Socket?=null
            var appLeg=false
            fun open(destination: String,port: Int): Pair<InputStream,OutputStream> {
                active?.close()
                phase=if(appLeg) "app.http" else "tcp"; val socket=session.dial(ip,config.port); active=socket
                var input=session.input(socket); var output=session.output(socket)
                if(config.tls) {
                    phase=if(appLeg) "app.http" else "tls"; val tls=TlsChannel(input,output,config.sni,config.port,config.sni,recipe,context=env.tlsContext())
                    input=tls.input; output=tls.output; metrics["tls"]=1
                }
                phase=if(appLeg) "app.http" else "ws"
                val key=WireBase64.encode(ByteArray(16).also { SecureRandom().nextBytes(it) })
                HttpWire.request(output,config.host,config.path.ifBlank { "/" },headers=mapOf("Upgrade" to "websocket","Sec-WebSocket-Version" to "13","Sec-WebSocket-Key" to key))
                val response=HttpWire.head(input)
                if(response.status!=101) throw UpgradeStatus(response.status)
                if(config.transport=="ws") {
                    val accept=WireBase64.encode(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                    require(response.headers["sec-websocket-accept"]==accept)
                    val ws=WebSocketStream(input,output); input=ws.input; output=ws.output
                }
                phase=if(appLeg) "app.http" else "proxy_or_egress"
                output.write(proxyHeader(config,destination,port)); output.flush()
                // VLESS's two-byte response is delayed until upstream data by many workers.
                // Consume it lazily, after sending HTTP or the inner TLS ClientHello.
                if(config.protocol=="vless") input=object: FilterInputStream(input) {
                    private var checked=false
                    private fun checkHeader() { if(!checked) { val h=HttpWire.readExact(`in`,2); if(h[0]!=0.toByte()) { phase=if(appLeg) "app.http" else "proxy.handshake"; throw IOException() }; HttpWire.readExact(`in`,h[1].toInt() and 255); checked=true; metrics["proxy_ack"]=1; if(!appLeg) phase="egress" } }
                    override fun read(): Int { checkHeader(); return `in`.read() }
                    override fun read(b: ByteArray,off: Int,len: Int): Int { checkHeader(); return `in`.read(b,off,len) }
                }
                return input to output
            }
            var egressOk=false
            for((host,path) in listOf("connectivitycheck.gstatic.com" to "/generate_204","www.msftconnecttest.com" to "/connecttest.txt")) {
                try {
                    val (input,output)=open(host,80)
                    HttpWire.request(output,host,path)
                    val response=HttpWire.response(input,128)
                    egressOk=if(host.startsWith("connectivitycheck")) response.status==204 else response.status==200 && String(response.body).trim()=="Microsoft Connect Test"
                } catch(e: Exception) {
                    if(phase !in setOf("egress","proxy_or_egress")) throw e
                    if(host.startsWith("www.msft")) throw e
                } finally { active?.close() }
                if(egressOk) break
            }
            if(!egressOk) return@run Evidence("ST3","baseline","egress",numbers=metrics)
            metrics["egress"]=1
            appLeg=true
            val (input,output)=open("www.google.com",443)
            phase="app.http"
            val tls=TlsChannel(input,output,"www.google.com",443,context=env.tlsContext())
            HttpWire.request(tls.output,"www.google.com","/generate_204")
            val response=HttpWire.response(tls.input,256)
            Evidence("ST3","baseline",if(response.status==204) "ok" else "app.http",response.status,numbers=metrics)
        }.copy(elapsedMs=env.now()-begin) }
        catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(e: UpgradeStatus) { Evidence("ST3","baseline","ws(${e.status})",e.status,env.now()-begin,numbers=metrics) }
        catch(e: Exception) { Evidence("ST3","baseline",phase,elapsedMs=env.now()-begin,numbers=metrics) }
    }
    private class UpgradeStatus(val status: Int): IOException()
    companion object {
        fun proxyHeader(c: TunnelConfig,host: String,port: Int): ByteArray {
            require(host.length in 1..253 && port in 1..65535)
            val out=ByteArrayOutputStream(); val d=DataOutputStream(out)
            if(c.protocol=="vless") {
                val uuid=UUID.fromString(c.credential); d.writeByte(0); d.writeLong(uuid.mostSignificantBits); d.writeLong(uuid.leastSignificantBits)
                d.writeByte(0); d.writeByte(1); d.writeShort(port); d.writeByte(2); d.writeByte(host.length); d.writeBytes(host)
            } else {
                require(c.protocol=="trojan")
                d.writeBytes(MessageDigest.getInstance("SHA-224").digest(c.credential.toByteArray()).joinToString("") { "%02x".format(it) })
                d.writeBytes("\r\n"); d.writeByte(1); d.writeByte(3); d.writeByte(host.length); d.writeBytes(host); d.writeShort(port); d.writeBytes("\r\n")
            }
            return out.toByteArray()
        }
        fun egress(input: InputStream,output: OutputStream,c: TunnelConfig,host: String,path: String): Evidence {
            var ack=false
            return try {
                output.write(proxyHeader(c,host,80)); HttpWire.request(output,host,path)
                if(c.protocol=="vless") {
                    val h=HttpWire.readExact(input,2)
                    if(h[0]!=0.toByte()) return Evidence("ST3","baseline","proxy.handshake")
                    HttpWire.readExact(input,h[1].toInt() and 255); ack=true
                }
                val r=HttpWire.response(input,256)
                Evidence("ST3","baseline",if(r.status==204) "ok" else "egress",r.status,numbers=mapOf("proxy_ack" to if(ack) 1L else 0L))
            } catch(e: Exception) { Evidence("ST3","baseline",if(ack) "egress" else "proxy_or_egress",numbers=mapOf("proxy_ack" to if(ack) 1L else 0L)) }
        }
    }
}
