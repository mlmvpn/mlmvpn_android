package com.mlmvpn.scanner.engines.cfdoctor

/** RFC 4648 codec kept JVM-only while supporting Android 7 (java.util.Base64 needs API 26). */
object WireBase64 {
    private const val alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    fun encode(bytes: ByteArray): String = buildString {
        var p=0
        while(p<bytes.size) {
            val a=bytes[p++].toInt() and 255
            val b=if(p<bytes.size) bytes[p++].toInt() and 255 else -1
            val c=if(p<bytes.size) bytes[p++].toInt() and 255 else -1
            append(alphabet[a ushr 2]); append(alphabet[((a and 3) shl 4) or (if(b<0) 0 else b ushr 4)])
            append(if(b<0) '=' else alphabet[((b and 15) shl 2) or (if(c<0) 0 else c ushr 6)])
            append(if(c<0) '=' else alphabet[c and 63])
        }
    }
    fun decode(text: String): ByteArray {
        val s=text.filterNot { it.isWhitespace() }; require(s.length%4!=1)
        val data=s.trimEnd('='); val padding=s.length-data.length
        require(padding<=2 && (padding==0 || s.length%4==0) && '=' !in data)
        val out=java.io.ByteArrayOutputStream(); var bits=0; var buffer=0
        data.forEach { ch ->
            val n=alphabet.indexOf(ch); require(n>=0)
            buffer=(buffer shl 6) or n; bits+=6
            if(bits>=8) { bits-=8; out.write((buffer ushr bits) and 255) }
        }
        require(bits<6 && (buffer and ((1 shl bits)-1))==0)
        return out.toByteArray()
    }
}
