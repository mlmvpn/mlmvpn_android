package com.mlmvpn.scanner.engines.cfdoctor

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress

object DnsWire {
    data class Answer(val addresses: List<String>, val rcode: Int, val ech: Boolean)
    fun query(host: String, type: Int = 1, id: Int = java.security.SecureRandom().nextInt(65536)): ByteArray {
        require(host.length in 1..253 && type in setOf(1,28,65))
        val b = ByteArrayOutputStream(); val d = DataOutputStream(b)
        d.writeShort(id); d.writeShort(0x0100); d.writeShort(1); repeat(3) { d.writeShort(0) }
        host.trimEnd('.').split('.').forEach { require(it.length in 1..63); d.writeByte(it.length); d.writeBytes(it) }
        d.writeByte(0); d.writeShort(type); d.writeShort(1); return b.toByteArray()
    }
    fun parse(b: ByteArray, id: Int): Answer {
        fun u16(p: Int): Int { require(p >= 0 && p+1 < b.size); return ((b[p].toInt() and 255) shl 8) or (b[p+1].toInt() and 255) }
        require(b.size >= 12 && u16(0) == id && u16(2) and 0x8000 != 0 && u16(2) and 0x0200 == 0)
        fun skipName(start: Int): Int {
            var p = start; var end = -1; val seen = mutableSetOf<Int>()
            while (true) {
                require(p in b.indices && seen.add(p) && seen.size <= 128)
                val n = b[p].toInt() and 255
                if (n == 0) return if (end >= 0) end else p+1
                if (n and 0xc0 == 0xc0) { if (end < 0) end=p+2; p=u16(p) and 0x3fff }
                else { require(n <= 63 && p+n < b.size); p+=n+1 }
            }
        }
        var p=12
        require(u16(4)<=8 && u16(6)<=256)
        repeat(u16(4)) { p=skipName(p); require(p+4<=b.size); p+=4 }
        val ips=mutableListOf<String>(); var ech=false
        repeat(u16(6)) {
            p=skipName(p); val type=u16(p); val clazz=u16(p+2); val n=u16(p+8); p+=10; require(p+n<=b.size)
            if (clazz==1 && ((type==1 && n==4) || (type==28 && n==16))) ips+=InetAddress.getByAddress(b.copyOfRange(p,p+n)).hostAddress!!
            if (type==65 && n>=3) {
                var q=skipName(p+2)
                while(q+4<=p+n) { val key=u16(q); val len=u16(q+2); q+=4; require(q+len<=p+n); if(key==5 && len>0) ech=true; q+=len }
            }
            p+=n
        }
        return Answer(ips.distinct(), u16(2) and 15, ech)
    }
}
