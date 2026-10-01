package com.mlmvpn.scanner.engines.cfdoctor

import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface NetEnv {
    fun resolve(host: String): List<InetAddress>
    fun socket(): Socket = Socket()
    fun datagram(): DatagramSocket = DatagramSocket()
    fun now(): Long = System.nanoTime()/1_000_000
    fun tlsContext(): javax.net.ssl.SSLContext = javax.net.ssl.SSLContext.getDefault()
}
class JvmNetEnv: NetEnv { override fun resolve(host: String)=InetAddress.getAllByName(host).toList() }

class DataBudget(val limit: Long) {
    val used=AtomicLong()
    fun count(n: Int) { if(n>0 && used.addAndGet(n.toLong())>limit) throw IOException("data_budget") }
}

/** One attempt owns every socket; cancellation/deadline closes them, including TLS writes. */
class ProbeSession(val env: NetEnv, private val budget: DataBudget, val timeoutMs: Int): Closeable {
    private val owned=mutableListOf<Closeable>()
    @Volatile private var closed=false
    private val deadline=timer.schedule({ close() },timeoutMs.toLong(),TimeUnit.MILLISECONDS)
    @Synchronized fun <T: Closeable> own(c: T): T { if(closed) { c.close(); throw InterruptedIOException() }; owned+=c; return c }
    fun dial(ip: String,port: Int): Socket {
        // Resolve BEFORE taking the lease. Host aliases must not bypass the one-IP limit.
        val address=InetAddress.getByName(ip)
        val lease=ipLocks.computeIfAbsent(address.hostAddress!!) { Semaphore(1,true) }
        if(!lease.tryAcquire(timeoutMs.toLong(),TimeUnit.MILLISECONDS)) throw SocketTimeoutException()
        var released=false
        val release=Closeable { synchronized(lease) { if(!released) { released=true; lease.release() } } }
        val socket=try { env.socket() } catch(e: Exception) { release.close(); throw e }
        // Closing the socket releases its lease even when the caller closes it between legs.
        val leased=object: Socket() {
            override fun getInputStream()=socket.getInputStream()
            override fun getOutputStream()=socket.getOutputStream()
            override fun close() { try { socket.close() } finally { release.close() } }
            override fun setSoTimeout(timeout: Int) { socket.soTimeout=timeout }
        }
        own(leased)
        try { socket.soTimeout=timeoutMs; socket.tcpNoDelay=true; socket.connect(InetSocketAddress(address,port),timeoutMs) }
        catch(e: Exception) { leased.close(); throw e }
        return leased
    }
    fun input(s: Socket): InputStream = input(s.getInputStream())
    fun input(source: InputStream): InputStream=object: FilterInputStream(source) {
        override fun read(): Int { val n=`in`.read(); if(n>=0) budget.count(1); return n }
        override fun read(b: ByteArray,off: Int,len: Int): Int { val n=`in`.read(b,off,len); budget.count(n); return n }
    }
    fun output(s: Socket): OutputStream=object: FilterOutputStream(s.getOutputStream()) {
        override fun write(b: Int) { budget.count(1); out.write(b) }
        override fun write(b: ByteArray,off: Int,len: Int) { budget.count(len); out.write(b,off,len) }
    }
    @Synchronized override fun close() { closed=true; owned.forEach { runCatching { it.close() } }; owned.clear(); deadline.cancel(false) }
    companion object {
        private val timer=Executors.newSingleThreadScheduledExecutor { r -> Thread(r,"doctor-deadlines").apply { isDaemon=true } }
        private val ipLocks=ConcurrentHashMap<String,Semaphore>()
        private val pool=Executors.newFixedThreadPool(6) { r -> Thread(r,"doctor-probe").apply { isDaemon=true } }
        /** The four stages' own workers: a stage test never queues behind the network sweep. */
        val stagePool: java.util.concurrent.ExecutorService=Executors.newFixedThreadPool(6) { r -> Thread(r,"doctor-stage").apply { isDaemon=true } }
        /**
         * Runs [block] on a worker. The deadline starts when the work STARTS, not when it is
         * queued: v1 started it at submission, so a stage test that waited behind forty network
         * probes had spent its whole time in the queue and was recorded as a timeout ("ws"/"tcp")
         * on a config that in fact worked.
         */
        suspend fun <T> run(env: NetEnv,budget: DataBudget,timeout: Int,executor: java.util.concurrent.ExecutorService=pool,block: (ProbeSession)->T): T = suspendCancellableCoroutine { cont ->
            val holder=java.util.concurrent.atomic.AtomicReference<ProbeSession?>()
            val cancelled=java.util.concurrent.atomic.AtomicBoolean(false)
            val task=executor.submit {
                if(cancelled.get()) return@submit
                val session=ProbeSession(env,budget,timeout); holder.set(session)
                if(cancelled.get()) session.close()
                try { val value=session.use(block); if(cont.isActive) cont.resume(value) } catch(e: Exception) { if(cont.isActive) cont.resumeWithException(e) }
            }
            cont.invokeOnCancellation { cancelled.set(true); holder.get()?.close(); task.cancel(true) }
        }
    }
}

data class ProbeSpec(val id: String, val host: String="api.cloudflare.com", val ip: String?=null,
    val port: Int=443, val sni: String=host, val path: String="/", val route: String="system",
    val kind: String="http", val privateEndpoint: Boolean=false, val headers: Map<String,String> = emptyMap(),
    val method: String="GET", val body: ByteArray=ByteArray(0), val maxBody: Int=4096,
    val recipe: SplitRecipe=SplitRecipe(), val protocol: String?=null)

class NetProbes(val env: NetEnv,val budget: DataBudget) {
    val dnsAnswers=ConcurrentHashMap<String,List<String>>()
    suspend fun run(spec: ProbeSpec, timeout: Int): Evidence {
        var phase="dns"; val begin=env.now(); val times=linkedMapOf<String,Long>()
        return try {
            ProbeSession.run(env,budget,timeout) attempt@{ session ->
                val addresses=spec.ip?.let { listOf(it) } ?: env.resolve(spec.host).mapNotNull { it.hostAddress }
                val ip=addresses.firstOrNull() ?: throw UnknownHostException()
                times["dns_ms"]=env.now()-begin
                if(spec.kind=="dns") dnsAnswers[spec.route]=addresses
                if(addresses.any(CfBogon::isBogon)) return@attempt Evidence(spec.id,spec.route,"dns_bogon")
                if(spec.kind=="dns") return@attempt Evidence(spec.id,spec.route,"ok",numbers=mapOf("count" to addresses.size.toLong()))
                phase="tcp"; val tcp=env.now(); val socket=session.dial(ip,spec.port); times["tcp_ms"]=env.now()-tcp
                if(spec.kind=="tcp") return@attempt Evidence(spec.id,spec.route,"ok",numbers=times)
                var input=session.input(socket); var output=session.output(socket)
                if(spec.port !in setOf(80,8080,8880,2052,2082,2086,2095)) {
                    phase="tls"; val t=env.now()
                    val tls=TlsChannel(input,output,spec.host,spec.port,spec.sni,spec.recipe,spec.protocol,context=env.tlsContext())
                    input=tls.input; output=tls.output; times["tls_ms"]=env.now()-t; times["tls"]=1
                }
                if(spec.kind=="tls") return@attempt Evidence(spec.id,spec.route,"ok",numbers=times)
                phase="http"; val t=env.now()
                HttpWire.request(output,spec.host,spec.path,spec.method,spec.headers,spec.body)
                val response=HttpWire.response(input,spec.maxBody,spec.method=="HEAD")
                times["http_ms"]=env.now()-t
                Evidence.http(spec.id,spec.route,response.status,response.headers,String(response.body,Charsets.UTF_8),spec.privateEndpoint).let {
                    it.copy(code=if(spec.id=="T6" && response.status in 200..499 && it.code!="http_blockpage") "ok" else it.code,numbers=it.numbers+times)
                }
            }.copy(elapsedMs=env.now()-begin, target=CfDoctorReport.hash(spec.ip ?: spec.host))
        } catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(e: Exception) {
            val elapsed=env.now()-begin
            val error=if(elapsed>=timeout && e is SocketException) SocketTimeoutException() else e
            Evidence.failure(spec.id,spec.route,phase,error,elapsed).copy(numbers=times,target=CfDoctorReport.hash(spec.ip ?: spec.host))
        }
    }

    suspend fun dns(id: String,resolver: String,mode: String,host: String="api.cloudflare.com",type: Int=1,sni: String=resolver,timeout: Int=4000): Evidence {
        val started=env.now(); var phase="dns"
        return try {
            ProbeSession.run(env,budget,timeout) { s ->
                val q=DnsWire.query(host,type); val xid=((q[0].toInt() and 255) shl 8) or (q[1].toInt() and 255)
                val bytes=when(mode) {
                    "udp" -> {
                        val socket=s.own(env.datagram()); socket.soTimeout=timeout
                        socket.connect(InetAddress.getByName(resolver),53)
                        budget.count(q.size); socket.send(DatagramPacket(q,q.size))
                        val p=DatagramPacket(ByteArray(4096),4096); socket.receive(p); budget.count(p.length); p.data.copyOf(p.length)
                    }
                    else -> {
                        phase="tcp"; val raw=s.dial(resolver,if(mode=="dot") 853 else 443)
                        phase="tls"; val tls=TlsChannel(s.input(raw),s.output(raw),resolver,if(mode=="dot") 853 else 443,sni,context=env.tlsContext())
                        phase="dns"
                        if(mode=="dot") {
                            tls.output.write(byteArrayOf((q.size ushr 8).toByte(),q.size.toByte())+q)
                            val n=HttpWire.readExact(tls.input,2); HttpWire.readExact(tls.input,((n[0].toInt() and 255) shl 8) or (n[1].toInt() and 255))
                        } else {
                            HttpWire.request(tls.output,resolver,"/dns-query","POST",mapOf("Content-Type" to "application/dns-message","Accept" to "application/dns-message"),q)
                            val response=HttpWire.response(tls.input,8192); if(response.status!=200) return@run Evidence(id,resolver,"http_status",response.status)
                            response.body
                        }
                    }
                }
                val a=DnsWire.parse(bytes,xid)
                dnsAnswers[id]=a.addresses
                Evidence(id,resolver,when { a.rcode!=0 -> "dns_nxdomain"; a.addresses.any(CfBogon::isBogon) -> "dns_bogon"; a.addresses.isEmpty() && type!=65 -> "dns_empty"; else -> "ok" },
                    numbers=mapOf("count" to a.addresses.size.toLong(),"ech" to if(a.ech) 1L else 0L))
            }.copy(elapsedMs=env.now()-started)
        } catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(e: Exception) { val ms=env.now()-started; Evidence.failure(id,resolver,phase,if(ms>=timeout && e is SocketException) SocketTimeoutException() else e,ms) }
    }
    suspend fun quic(ip: String,timeout: Int): Evidence {
        val start=env.now()
        return try { ProbeSession.run(env,budget,timeout) { s ->
            val q=QuicProbe.packet(); val socket=s.own(env.datagram()); socket.soTimeout=timeout; socket.connect(InetAddress.getByName(ip),443)
            budget.count(q.bytes.size); socket.send(DatagramPacket(q.bytes,q.bytes.size))
            val r=DatagramPacket(ByteArray(4096),4096); socket.receive(r); budget.count(r.length)
            Evidence("Q1",ip,if(QuicProbe.validResponse(r.data.copyOf(r.length),q)) "ok" else "udp_unknown")
        }.copy(elapsedMs=env.now()-start) } catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(e: Exception) { val ms=env.now()-start; Evidence.failure("Q1",ip,"udp",if(ms>=timeout && e is SocketException) SocketTimeoutException() else e,ms) }
    }
}
