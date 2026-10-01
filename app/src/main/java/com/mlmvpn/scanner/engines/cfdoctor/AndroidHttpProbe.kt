package com.mlmvpn.scanner.engines.cfdoctor

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** OkHttp supplies real HTTP/2 framing/HPACK rather than sending HTTP/1 over an h2 ALPN. */
object AndroidHttpProbe {
    suspend fun h2(spec: ProbeSpec,env: AndroidNetEnv,budget: DataBudget,timeout: Int): Evidence = suspendCancellableCoroutine { cont ->
        val network=env.picked?.network
        if(network==null) { cont.resume(Evidence(spec.id,"h2","tcp_unavailable")); return@suspendCancellableCoroutine }
        val started=env.now()
        val client=OkHttpClient.Builder().socketFactory(network.socketFactory).dns { env.resolve(it) }
            .protocols(listOf(Protocol.HTTP_2,Protocol.HTTP_1_1)).callTimeout(timeout.toLong(),TimeUnit.MILLISECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        val call=client.newCall(Request.Builder().url("https://${spec.host}${spec.path}").build())
        fun clean() { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        call.enqueue(object: Callback {
            override fun onFailure(call: Call,e: IOException) { clean(); if(cont.isActive) cont.resume(Evidence.failure(spec.id,"h2","http",e,env.now()-started)) }
            override fun onResponse(call: Call,response: Response) {
                val row=try { response.use {
                    val body=it.peekBody(8192).string(); budget.count(body.toByteArray().size)
                    Evidence.http(spec.id,"h2",it.code,it.headers.names().associateWith { name -> it.header(name).orEmpty() },body,false,env.now()-started)
                        .let { r -> r.copy(code=if(response.protocol==Protocol.HTTP_2) r.code else "h2_not_negotiated",numbers=r.numbers+("h2" to if(response.protocol==Protocol.HTTP_2) 1L else 0L)) }
                } } catch(e: Exception) { Evidence.failure(spec.id,"h2","http",e,env.now()-started) }
                clean(); if(cont.isActive) cont.resume(row)
            }
        })
        cont.invokeOnCancellation { call.cancel(); clean() }
    }
}
