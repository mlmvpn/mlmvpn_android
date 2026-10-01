package com.mlmvpn.scanner.engines.cfdoctor

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

interface DoctorProbe {
    fun addresses(): List<String> = emptyList()
    suspend fun probe(spec: ProbeSpec,timeout: Int): Evidence
    suspend fun dns(id: String,resolver: String,mode: String,type: Int=1,sni: String=resolver,timeout: Int=4000): Evidence
    suspend fun quic(ip: String,timeout: Int): Evidence
}
open class NetworkDoctorProbe(private val net: NetProbes): DoctorProbe {
    override fun addresses()=net.dnsAnswers.values.flatten().filter(CfBogon::isCloudflare).distinct()
    override suspend fun probe(spec: ProbeSpec,timeout: Int)=net.run(spec,timeout)
    override suspend fun dns(id: String,resolver: String,mode: String,type: Int,sni: String,timeout: Int)=net.dns(id,resolver,mode,host=if(type==65) "cloudflare-ech.com" else "api.cloudflare.com",type=type,sni=sni,timeout=timeout)
    override suspend fun quic(ip: String,timeout: Int)=net.quic(ip,timeout)
}
data class DoctorProgress(val running: Boolean=false,val complete: Boolean=false,val tier: Int=0,
    val rows: List<Evidence> = emptyList(),val flags: Set<String> = emptySet(),val decisions: List<String> = emptyList(),
    val elapsedMs: Long=0,val current: String="",val bytes: Long=0)

class DoctorRunner(val plan: DoctorPlan,private val probes: DoctorProbe,
    private val budget: DataBudget=DataBudget(plan.maxBytes),
    private val stages: suspend (DoctorRunner)->Unit = {},
    private val adaptiveStages: suspend (DoctorRunner,Boolean)->Unit = { _,_-> },
    private val persist: (DoctorProgress)->Unit = {}) {
    private val mutable=MutableStateFlow(DoctorProgress())
    val state: StateFlow<DoctorProgress> = mutable
    private val slots=Semaphore(6)
    private val locks=ConcurrentHashMap<String,Semaphore>()
    private var started=0L
    private var timeout=plan.timeoutMs
    private val executed=mutableSetOf<String>()
    private val expanded=mutableSetOf<String>()
    private fun now()=System.nanoTime()/1_000_000
    @Synchronized private fun update(block: (DoctorProgress)->DoctorProgress) {
        mutable.value=block(mutable.value).copy(elapsedMs=if(started==0L) 0 else now()-started,bytes=budget.used.get())
        persist(mutable.value)
    }
    fun record(row: Evidence) { update { p -> if(!p.running || p.rows.size>=plan.maxAttempts) p else p.copy(rows=p.rows+row.copy(atMs=now()-started),current=row.id) } }
    fun decision(value: String) { update { it.copy(decisions=it.decisions+value) } }
    private fun available()=state.value.rows.size<plan.maxAttempts && now()-started<plan.maxMs && budget.used.get()<plan.maxBytes
    private suspend fun attempt(id: String,key: String,fragment: Boolean=false,block: suspend (Int)->Evidence) {
        slots.withPermit { locks.getOrPut(key) { Semaphore(1) }.withPermit inner@{
            currentCoroutineContext().ensureActive()
            if(!available()) { decision("skip:$id:budget"); return@inner }
            val ms=if(fragment) maxOf(20000,timeout) else timeout
            val r=withTimeoutOrNull(ms.toLong()+250) { block(ms) } ?: Evidence(id,"deadline","timeout")
            record(r)
        } }
    }
    private suspend fun probe(s: ProbeSpec)=attempt(s.id,s.ip ?: s.host,s.id.startsWith("F")) { probes.probe(s,it) }
    private suspend fun dns(id: String,ip: String,mode: String="doh",type: Int=1,sni: String=ip)=attempt(id,ip) { probes.dns(id,ip,mode,type,sni,it) }
    suspend fun run(full: Boolean) {
        check(!state.value.running)
        started=now(); update { DoctorProgress(running=true) }
        try {
            val completed=withTimeoutOrNull(plan.maxMs) {
                decision("tier0:triage")
                withTimeoutOrNull(plan.tiersMs[0]) { coroutineScope {
                    listOf("www.google.com","www.wikipedia.org","www.msftconnecttest.com","www.aparat.com","www.digikala.com").forEachIndexed { i,h -> launch { probe(ProbeSpec("T6",h,route=if(i<3) "foreign" else "domestic",method="HEAD")) } }
                    launch { probe(ProbeSpec("D1",kind="dns")) }; launch { dns("D2","8.8.8.8") }
                    plan.ips.take(8).forEach { ip -> launch { probe(ProbeSpec("T1",ip=ip,route=ip,kind="tcp")) } }
                    listOf("api.cloudflare.com","www.cloudflare.com").forEach { h -> launch { probe(ProbeSpec("S1",h,ip=plan.ips.first(),route=h)) } }
                    launch { probe(ProbeSpec("X1",path="/client/v4/ips")) }
                    launch { probe(ProbeSpec("X2","www.cloudflare.com",path="/cdn-cgi/trace")) }
                } } ?: decision("tier0:deadline")
                var flags=TriageClassifier.classify(state.value.rows); update { it.copy(flags=flags,tier=1) }
                val rtts=state.value.rows.filter { it.id=="T6" && it.ok }.mapNotNull { it.numbers["tcp_ms"] }.sorted()
                if(rtts.isNotEmpty() && rtts[rtts.size/2]>plan.slowRttMs) { timeout*=2; decision("timeout:rtt_x2") }
                if(flags.any { it in setOf("NO_CONNECTIVITY","NO_INTERNATIONAL") }) {
                    decision("early_stop:${flags.sorted().joinToString(",")}")
                    decision("skip:ST1_ST2_ST3_core:controls_failed")
                    return@withTimeoutOrNull true
                }
                decision("tier1:baseline_stages")
                withTimeoutOrNull(plan.tiersMs[1]) {
                    // Discover anchors before scheduling anything that depends on them. Keep
                    // baseline stages bounded so they cannot starve the fixed comparison core.
                    runMethods(setOf("T1"),"core")
                    // The four stages and the fixed comparison core side by side: neither starves
                    // the other of the tier's time, and the stages use their own connections.
                    coroutineScope {
                        launch { withTimeoutOrNull(STAGES_MS) { stages(this@DoctorRunner) } ?: decision("baseline:deadline") }
                        launch { runMethods(plan.core,"core") }
                    }
                    flags=TriageClassifier.classify(state.value.rows); update { it.copy(flags=flags) }
                    val adaptive=flags.flatMap { plan.mapping[it].orEmpty() }.toSet()
                    if("T1" in adaptive) runMethods(setOf("T1"),"adaptive")
                    runMethods(adaptive-"T1","adaptive")
                } ?: decision("tier1:deadline")
                val ambiguous=flags.isEmpty() || state.value.rows.none { it.ok && it.http in 200..299 } || StageVerdict.chain(state.value.rows,"ST3").verdict==Verdict.FAIL
                withTimeoutOrNull(VARIANTS_MS) { adaptiveStages(this@DoctorRunner,full) } ?: decision("config_variants:deadline")
                if(full || ambiguous) {
                    update { it.copy(tier=2) }; decision("tier2:${if(full) "requested" else "unresolved"}")
                    withTimeoutOrNull(plan.tiersMs[2]) {
                        runMethods(setOf("T1"),"full")
                        runMethods(DoctorPlan.ALL-expanded-"T1","full")
                    } ?: decision("tier2:deadline")
                } else decision("skip:tier2:resolved")
                true
            }
            if(completed==null) decision("stop:total_budget")
            // "Complete" means everything planned ran: no tier or stage hit its deadline.
            val cut=state.value.decisions.any { it.endsWith(":deadline") || it.startsWith("stop:") || it.contains(":budget") }
            update { it.copy(complete=completed==true && available() && !cut) }
        } catch(e: CancellationException) { decision("stop:cancelled"); throw e }
        catch(e: Exception) { decision("stop:internal_error") }
        finally {
            val present=(state.value.rows.map { it.id }+state.value.rows.flatMap { Regex("(?:^|[:.])([CF][0-9]+)(?:$|[.:])").findAll(it.route).map { m -> m.groupValues[1] }.toList() }+
                (if(state.value.rows.any { it.id=="ST3" }) listOf("C1") else emptyList())+
                (if(state.value.rows.any { it.id=="ST2" && it.code!="host_only" }) listOf("W2") else emptyList())).toSet()
            (DoctorPlan.ALL-present).filter { id -> state.value.decisions.none { it.startsWith("skip:$id:") } }.forEach { decision("skip:$it:not_reached_or_not_applicable") }
            update { it.copy(running=false) }
        }
    }
    private fun anchors(): List<String> {
        val good=state.value.rows.filter { it.id=="T1" && it.ok }.map { it.route }.filter(CfBogon::isCloudflare)
        return (good+probes.addresses()).distinctBy { it.split('.').take(2) }.take(3)
    }
    private suspend fun runMethods(ids: Set<String>,reason: String)=coroutineScope {
        val anchors=anchors()
        val jobs=mutableListOf<Job>()
        for(id in ids) {
            if(id in setOf("H3","H4")) continue // Platform sockets run after raw probes release their leases.
            if(!available()) { decision("skip:$id:budget"); continue }
            if(reason=="core" && id in executed || reason!="core" && id in expanded) continue
            if(id.startsWith("S") || id.startsWith("F") || id in setOf("T2","T3","H1","H2")) {
                if(anchors.isEmpty()) { decision("skip:$id:no_anchor"); continue }
            }
            executed+=id
            if(reason!="core") expanded+=id
            if(id in setOf("Q2","R2","F10","F11")) { decision("skip:$id:requires_device_validation"); continue }
            if(id.startsWith("C") || id in setOf("W1","W2","R1")) { decision("defer:$id:stage_adapter"); continue }
            decision("run:$id:$reason")
            jobs+=launch {
                repeat(if(id in setOf("X1","X2","S1","F1","F3")) plan.repeats else 1) {
                    when(id) {
                        "T6" -> Unit // Recorded in triage.
                        "D1" -> probe(ProbeSpec(id,kind="dns"))
                        "D2" -> dns(id,"8.8.8.8")
                        "D3" -> dns(id,"8.8.4.4")
                        "D4" -> dns(id,"1.1.1.1")
                        "D5" -> dns(id,"94.140.14.14")
                        "D6" -> dns(id,"9.9.9.9")
                        "D7" -> listOf("8.8.8.8","1.1.1.1").forEach { dns(id,it,"udp") }
                        "D8" -> dns(id,"8.8.8.8","dot")
                        "D9" -> { dns(id,"8.8.8.8",sni="dns.google"); dns(id,"1.1.1.1",sni="cloudflare-dns.com") }
                        "D10" -> dns(id,"1.1.1.1",sni="www.microsoft.com")
                        "D11" -> listOf("8.8.8.8","1.1.1.1").forEach { dns(id,it,type=65) }
                        "T1" -> coroutineScope { (if(reason=="core") plan.ips.take(12) else plan.ips).forEach { ip -> launch { probe(ProbeSpec(id,ip=ip,route=ip,kind="tcp")) } } }
                        "T2" -> anchors.take(if(reason=="core") 1 else 3).forEach { ip -> plan.ports.take(if(reason=="core") 3 else 6).forEach { p -> probe(ProbeSpec(id,ip=ip,port=p,route="$ip:$p")) } }
                        "T3" -> anchors.forEach { ip -> listOf(80,8080,8880,2052,2082,2086,2095).forEach { p -> probe(ProbeSpec(id,"www.cloudflare.com",ip=ip,port=p,route="$ip:$p")) } }
                        "T4" -> listOf("162.159.192.1","162.159.197.1").forEach { ip -> probe(ProbeSpec(id,ip=ip,route=ip,kind="tcp")) }
                        "T5" -> probe(ProbeSpec(id,ip="2606:4700::1111",route="ipv6",kind="tcp"))
                        "S1" -> anchors.take(2).forEach { ip -> (if(reason=="core") plan.hosts.take(4)+"${java.util.UUID.randomUUID().toString().take(8)}.workers.dev" else plan.hosts).forEach { h -> probe(ProbeSpec(id,h,ip=ip,route=if(h.endsWith(".workers.dev")) "random_workers" else h)) } }
                        "S2","S3","S4","S5","S6" -> anchors.take(2).forEach { ip ->
                            val snis=when(id) { "S2" -> listOf(""); "S3" -> listOf("aPi.ClOuDfLaRe.CoM"); "S4" -> listOf("api.cloudflare.com."); "S5" -> listOf("www.microsoft.com","www.google.com","github.githubassets.com","www.apple.com","dns.google"); else -> listOf("www.cloudflare.com") }
                            snis.forEach { name -> probe(ProbeSpec(id,ip=ip,sni=name,route=if(name.isEmpty()) "no_sni" else name,path="/client/v4/ips")) }
                        }
                        "H1","H2" -> anchors.take(2).forEach { ip -> probe(ProbeSpec(id,ip=ip,protocol=if(id=="H1") "TLSv1.2" else "TLSv1.3",path="/client/v4/ips")) }
                        "H3","H4" -> probe(ProbeSpec(id,path="/client/v4/ips",kind="h2"))
                        "X1" -> probe(ProbeSpec(id,path="/client/v4/ips"))
                        "X2" -> probe(ProbeSpec(id,"www.cloudflare.com",path="/cdn-cgi/trace"))
                        "X3" -> probe(ProbeSpec(id,"speed.cloudflare.com",path="/__down?bytes=2000000",maxBody=2000000))
                        "X4" -> probe(ProbeSpec(id,"speed.cloudflare.com",path="/__up",method="POST",body=ByteArray(200000)))
                        "Q1" -> plan.ips.take(3).forEach { ip -> attempt(id,ip) { probes.quic(ip,it) } }
                        else -> if(id.startsWith("F")) anchors.take(2).forEach { ip ->
                            val recipe=plan.recipes[id] ?: SplitRecipe()
                            val failedHost=state.value.rows.firstOrNull { it.id=="S1" && it.code.startsWith("tls_") && it.route in plan.hosts }?.route ?: "api.cloudflare.com"
                            val delays=if(id=="F7") plan.fragmentDelays else listOf(recipe.delayMs)
                            delays.forEach { delay -> probe(ProbeSpec(id,failedHost,ip=ip,route="${ip}:d$delay",path=if(failedHost=="api.cloudflare.com") "/client/v4/ips" else "/",recipe=recipe.copy(delayMs=delay))) }
                        }
                    }
                }
            }
        }
        jobs.joinAll()
        for(id in ids.intersect(setOf("H3","H4"))) {
            if(id in expanded) continue
            if(!available()) { decision("skip:$id:budget"); continue }
            executed+=id; expanded+=id; decision("run:$id:$reason")
            probe(ProbeSpec(id,path="/client/v4/ips",kind="h2"))
        }
    }
    companion object {
        /** ST1..ST4 together. */
        const val STAGES_MS = 200_000L
        /** Config variations after the core. */
        const val VARIANTS_MS = 150_000L
    }
}
