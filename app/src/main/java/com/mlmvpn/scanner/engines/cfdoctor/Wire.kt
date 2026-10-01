package com.mlmvpn.scanner.engines.cfdoctor

import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.net.ssl.*

object TlsRecords {
    fun fragment(record: ByteArray, at: Int): ByteArray {
        require(record.size>=5)
        val n=((record[3].toInt() and 255) shl 8) or (record[4].toInt() and 255)
        require(n+5<=record.size && at in 1 until n)
        val out=ByteArrayOutputStream()
        listOf(0 to at, at to n).forEach { (start,end) ->
            out.write(record,0,3); out.write((end-start) ushr 8); out.write((end-start) and 255); out.write(record,5+start,end-start)
        }
        out.write(record,5+n,record.size-5-n)
        return out.toByteArray()
    }
    fun sniOffset(record: ByteArray, sni: String): Int? {
        val needle=sni.toByteArray(Charsets.US_ASCII)
        if(needle.isEmpty()) return null
        return (5..record.size-needle.size).firstOrNull { p -> needle.indices.all { record[p+it]==needle[it] } }
    }
}

data class SplitRecipe(val at: Int = 0, val chunk: Int = 0, val delayMs: Int = 0, val record: Boolean = false, val sni: Boolean = false)
object ClientHelloSplit {
    fun write(out: OutputStream, hello: ByteArray, recipe: SplitRecipe, host: String) {
        val data=if(recipe.record && hello.size>7) TlsRecords.fragment(hello, (hello.size-5)/2) else hello
        val first=if(recipe.sni) (TlsRecords.sniOffset(data,host)?.plus(host.length/2) ?: data.size/2) else recipe.at
        var p=0
        while(p<data.size) {
            if(Thread.currentThread().isInterrupted) throw InterruptedIOException()
            val n=when { p==0 && first>0 -> first; recipe.chunk>0 -> recipe.chunk; else -> data.size-p }.coerceAtMost(data.size-p)
            out.write(data,p,n); out.flush(); p+=n
            if(p<data.size && recipe.delayMs>0) Thread.sleep(recipe.delayMs.toLong())
        }
    }
    fun recipe(id: String): SplitRecipe = when(id) {
        "F1" -> SplitRecipe(at=1)
        "F2" -> SplitRecipe(at=5)
        "F3" -> SplitRecipe(at=5,chunk=1)
        "F4" -> SplitRecipe(sni=true)
        "F5" -> SplitRecipe(sni=true,chunk=16)
        "F6" -> SplitRecipe(chunk=16,delayMs=10)
        "F7" -> SplitRecipe(chunk=32,delayMs=50)
        "F8" -> SplitRecipe(record=true)
        "F9" -> SplitRecipe(record=true,chunk=32)
        "F12" -> SplitRecipe(chunk=75,delayMs=15)
        "F13" -> SplitRecipe(chunk=150,delayMs=15)
        "F14" -> SplitRecipe(chunk=1,delayMs=1)
        "F15" -> SplitRecipe(at=5,chunk=1,delayMs=1)
        else -> SplitRecipe()
    }
}

/** TLS over arbitrary streams, also used for TLS inside a WebSocket proxy. */
class TlsChannel(private val source: InputStream, private val sink: OutputStream, host: String, port: Int,
                 sni: String = host, recipe: SplitRecipe = SplitRecipe(), protocol: String? = null,
                 context: SSLContext = SSLContext.getDefault()) {
    private val engine=context.createSSLEngine(host,port).apply {
        useClientMode=true
        sslParameters=sslParameters.apply {
            endpointIdentificationAlgorithm="HTTPS"
            serverNames=if(sni.isEmpty()) emptyList() else listOf(SNIHostName(sni))
        }
        if(protocol!=null) enabledProtocols=arrayOf(protocol)
    }
    private val incoming=ByteBuffer.allocate(65536).apply { limit(0) }
    private val clear=ByteBuffer.allocate(65536)
    private var pending=ByteArrayInputStream(ByteArray(0))
    private var first=true
    val version: String get()=engine.session.protocol
    init {
        engine.beginHandshake()
        var rounds=0
        while(engine.handshakeStatus!=SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING && engine.handshakeStatus!=SSLEngineResult.HandshakeStatus.FINISHED) {
            require(rounds++<512)
            when(engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> tasks()
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrap(ByteBuffer.allocate(0),recipe,sni)
                else -> unwrap()
            }
        }
    }
    private fun tasks() { while(true) (engine.delegatedTask ?: break).run() }
    private fun wrap(buf: ByteBuffer, recipe: SplitRecipe = SplitRecipe(), sni: String = "") {
        val packet=ByteBuffer.allocate(65536)
        val r=engine.wrap(buf,packet)
        if(r.status==SSLEngineResult.Status.CLOSED) throw EOFException()
        if(r.status==SSLEngineResult.Status.BUFFER_OVERFLOW) throw IOException("tls_record_limit")
        packet.flip(); val bytes=ByteArray(packet.remaining()); packet.get(bytes)
        if(first && bytes.isNotEmpty()) { ClientHelloSplit.write(sink,bytes,recipe,sni); first=false }
        else { sink.write(bytes); sink.flush() }
        tasks()
    }
    private fun refill() {
        incoming.compact()
        if(!incoming.hasRemaining()) throw IOException("tls_record_limit")
        val n=source.read(incoming.array(), incoming.position(), incoming.remaining())
        if(n<0) throw EOFException()
        incoming.position(incoming.position()+n); incoming.flip()
    }
    private fun unwrap(): Int {
        while(true) {
            clear.clear()
            val r=engine.unwrap(incoming,clear)
            when(r.status) {
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> { refill(); continue }
                SSLEngineResult.Status.CLOSED -> return -1
                SSLEngineResult.Status.BUFFER_OVERFLOW -> throw IOException("tls_clear_limit")
                else -> Unit
            }
            tasks()
            if(engine.handshakeStatus==SSLEngineResult.HandshakeStatus.NEED_WRAP) wrap(ByteBuffer.allocate(0))
            clear.flip(); val bytes=ByteArray(clear.remaining()); clear.get(bytes); pending=ByteArrayInputStream(bytes)
            return bytes.size
        }
    }
    val input: InputStream = object: InputStream() {
        override fun read(): Int { val b=ByteArray(1); return if(read(b,0,1)<0) -1 else b[0].toInt() and 255 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if(len==0) return 0
            while(pending.available()==0) if(unwrap()<0) return -1
            return pending.read(b,off,len)
        }
    }
    val output: OutputStream = object: OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) {
            val buf=ByteBuffer.wrap(b,off,len)
            while(buf.hasRemaining()) wrap(buf)
        }
    }
}

object HttpWire {
    data class Response(val status: Int, val headers: Map<String,String>, val body: ByteArray)
    fun readHead(input: InputStream): String {
        val b=ByteArrayOutputStream(); var matched=0
        val end=byteArrayOf(13,10,13,10)
        while(b.size()<16384) {
            val c=input.read(); if(c<0) throw EOFException(); b.write(c)
            matched=if(c==end[matched].toInt()) matched+1 else if(c==13) 1 else 0
            if(matched==4) return b.toString("ISO-8859-1")
        }
        throw IOException("header_limit")
    }
    fun request(out: OutputStream, host: String, path: String, method: String = "GET", headers: Map<String,String> = emptyMap(), body: ByteArray = ByteArray(0)) {
        require(listOf(host,path,method).none { it.contains('\r') || it.contains('\n') })
        require(headers.all { (k,v) -> !k.contains('\r') && !k.contains('\n') && !v.contains('\r') && !v.contains('\n') })
        out.write(buildString {
            append("$method $path HTTP/1.1\r\nHost: $host\r\nConnection: ${if(headers.keys.any { it.equals("upgrade",true) }) "Upgrade" else "close"}\r\nAccept-Encoding: identity\r\n")
            headers.forEach { (k,v) -> append("$k: $v\r\n") }
            if(body.isNotEmpty()) append("Content-Length: ${body.size}\r\n")
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)); out.write(body); out.flush()
    }
    fun head(input: InputStream): Response {
        val lines=readHead(input).split("\r\n")
        val status=lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("http_status")
        val h=lines.drop(1).filter { it.contains(':') }.associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        return Response(status,h,ByteArray(0))
    }
    fun response(input: InputStream, limit: Int, headOnly: Boolean=false): Response {
        val h=head(input); if(headOnly || h.status in listOf(101,204,304)) return h
        val b=ByteArrayOutputStream()
        if(h.headers["transfer-encoding"]?.equals("chunked",true)==true) {
            while(b.size()<limit) {
                val line=ByteArrayOutputStream()
                while(line.size()<128) { val c=input.read(); if(c<0) throw EOFException(); if(c==10) break; line.write(c) }
                val n=line.toString("US-ASCII").trim().substringBefore(';').toIntOrNull(16) ?: throw IOException("chunk_format")
                require(n>=0); if(n==0) break
                val count=minOf(n,limit-b.size()); b.write(readExact(input,count))
                if(count<n) break
                require(input.read()==13 && input.read()==10)
            }
        } else {
            val length=h.headers["content-length"]?.toLongOrNull()?.coerceIn(0,limit.toLong())?.toInt() ?: limit
            val buf=ByteArray(4096)
            while(b.size()<length) {
                val n=input.read(buf,0,minOf(buf.size,length-b.size()))
                if(n<0) { if(h.headers["content-length"]!=null) throw EOFException(); break }
                b.write(buf,0,n)
            }
        }
        return h.copy(body=b.toByteArray())
    }
    fun readExact(input: InputStream,n: Int): ByteArray {
        require(n in 0..1048576); val b=ByteArray(n); var p=0
        while(p<n) { val count=input.read(b,p,n-p); if(count<0) throw EOFException(); p+=count }; return b
    }
}

class WebSocketStream(private val source: InputStream, private val sink: OutputStream) {
    private val random=SecureRandom()
    private var pending=ByteArrayInputStream(ByteArray(0))
    private fun frame(op: Int,b: ByteArray) {
        require(b.size<=65535)
        sink.write(0x80 or op)
        if(b.size<126) sink.write(0x80 or b.size) else { sink.write(0x80 or 126); sink.write(b.size ushr 8); sink.write(b.size and 255) }
        val mask=ByteArray(4).also(random::nextBytes); sink.write(mask)
        sink.write(ByteArray(b.size) { (b[it].toInt() xor mask[it%4].toInt()).toByte() }); sink.flush()
    }
    val output: OutputStream=object: OutputStream() {
        override fun write(b: Int)=write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray,off: Int,len: Int) { var p=off; while(p<off+len) { val n=minOf(16384,off+len-p); frame(2,b.copyOfRange(p,p+n)); p+=n } }
    }
    val input: InputStream=object: InputStream() {
        override fun read(): Int { val b=ByteArray(1); return if(read(b,0,1)<0) -1 else b[0].toInt() and 255 }
        override fun read(b: ByteArray,off: Int,len: Int): Int {
            if(len==0) return 0
            while(pending.available()==0) {
                val h=source.read(); val s=source.read(); if(h<0 || s<0) return -1
                require(h and 0x70==0 && s and 0x80==0)
                var n=(s and 127).toLong()
                if(n==126L) { val v=HttpWire.readExact(source,2); n=((v[0].toInt() and 255)*256+(v[1].toInt() and 255)).toLong() }
                if(n==127L) { val v=HttpWire.readExact(source,8); n=ByteBuffer.wrap(v).long }
                require(n in 0..65536)
                val payload=HttpWire.readExact(source,n.toInt())
                when(h and 15) { 8 -> return -1; 9 -> frame(10,payload); 10 -> Unit; 0,2 -> pending=ByteArrayInputStream(payload); else -> throw IOException("ws_opcode") }
            }
            return pending.read(b,off,len)
        }
    }
}

object QuicProbe {
    data class Packet(val bytes: ByteArray,val dcid: ByteArray,val scid: ByteArray)
    fun packet(): Packet {
        val random=SecureRandom(); val dcid=ByteArray(8).also(random::nextBytes); val scid=ByteArray(8).also(random::nextBytes)
        val out=ByteArrayOutputStream(); out.write(0xc0); out.write(byteArrayOf(0xfa.toByte(),0xfa.toByte(),0xfa.toByte(),0xfa.toByte()))
        out.write(dcid.size); out.write(dcid); out.write(scid.size); out.write(scid)
        out.write(ByteArray(1200-out.size()).also(random::nextBytes)); return Packet(out.toByteArray(),dcid,scid)
    }
    fun validResponse(b: ByteArray,q: Packet): Boolean = runCatching {
        require(b.size>=7 && b[0].toInt() and 0x80!=0 && b.sliceArray(1..4).all { it==0.toByte() })
        val d=b[5].toInt() and 255; require(d==q.scid.size && b.copyOfRange(6,6+d).contentEquals(q.scid))
        val s=b[6+d].toInt() and 255; require(s==q.dcid.size && b.copyOfRange(7+d,7+d+s).contentEquals(q.dcid))
        val p=7+d+s; require(b.size>p && (b.size-p)%4==0); true
    }.getOrDefault(false)
}
