package com.mlmvpn.scanner.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.engines.vpngate.*
import com.mlmvpn.scanner.ui.theme.*
import kotlinx.coroutines.launch

/**
 * "More servers" — browse the accumulated archive, test it, and promote healthy servers into
 * the main list.
 *
 * Two steps on purpose. Picking a place is a different decision from picking a machine, and
 * showing a thousand hostnames at once is exactly the confusion the user asked to avoid:
 *   1. countries (grouped by continent, multi-selectable)
 *   2. the servers of that selection, with a real ping test and check-boxes.
 */
@Composable
fun VpnGateBrowseScreen(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { VpnGateRepository(context) }

    val pool by VpnGatePool.poolFlow.collectAsState()
    val kept by VpnGatePool.keptFlow.collectAsState()
    val pings by VpnGateStore.pingsFlow.collectAsState()
    val handshakes by VpnGateStore.handshakesFlow.collectAsState()
    // Collected rather than VpnGateSweep.isRunning() — see the note in ServerPickerDialog.
    // A plain read is not observable, so the prune button would never reappear once a
    // second sweep finished.
    val sweep by VpnGateSweep.stateFlow.collectAsState()
    val loading by repo.loadingFlow.collectAsState()

    var selectedCountries by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showServers by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pinging by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var snack by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { VpnGatePool.load(context) }

    LaunchedEffect(snack) {
        if (snack != null) {
            kotlinx.coroutines.delay(2600)
            snack = null
        }
    }

    val servers = remember(pool) { pool.values.map { it.server } }

    // country code -> servers, and the continent buckets built off it.
    val byCountry = remember(servers) { servers.groupBy { it.countryShort } }
    val byContinent = remember(byCountry) {
        byCountry.keys
            .groupBy { VpnGateGeo.continentOf(it) }
            .toSortedMap(compareBy { it.ordinal })
    }

    // Ordered by the real handshake first, then ping, so results reorder themselves as each
    // test lands and the servers that will actually connect end up on top by themselves.
    val selectedServers = remember(selectedCountries, servers, pings, handshakes) {
        servers.filter { it.countryShort in selectedCountries }
            .sortedWith(
                compareBy<VpnGateServer> {
                    when (val r = handshakes[it.hostName]) {
                        is SoftEtherProbe.Result.Ok -> r.ms
                        null -> 1_000_000
                        else -> 2_000_000
                    }
                }.thenBy {
                    val p = pings[it.hostName] ?: Int.MAX_VALUE - 1
                    if (p <= 0) Int.MAX_VALUE else p
                }
            )
    }

    Box(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- header ------------------------------------------------------------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp, end = 8.dp),
            ) {
                IconButton(onClick = { if (showServers) showServers = false else onDismiss() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "بازگشت", tint = TextMuted)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (showServers) "انتخاب سرور" else "سرورهای بیشتر",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (showServers) "${selectedServers.size} سرور در ${selectedCountries.size} کشور"
                               else "${servers.size} سرور آرشیو شده در ${byCountry.size} کشور",
                        color = TextDim,
                        fontSize = 11.sp,
                    )
                }
                if (loading || pinging) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Primary
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    IconButton(onClick = {
                        scope.launch {
                            repo.refresh(force = true)
                            val added = repo.newlyDiscoveredFlow.value
                            snack = if (added > 0) "$added سرور تازه به آرشیو اضافه شد"
                                    else "سرور جدیدی پیدا نشد"
                        }
                    }) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "دریافت سرورهای تازه", tint = Primary)
                    }
                }
            }

            Divider(color = BorderDark, thickness = 1.dp)

            if (!showServers) {
                CountryStep(
                    byContinent = byContinent,
                    byCountry = byCountry,
                    selected = selectedCountries,
                    onToggleCountry = { cc ->
                        selectedCountries = if (cc in selectedCountries) selectedCountries - cc
                                            else selectedCountries + cc
                    },
                    onToggleContinent = { codes ->
                        selectedCountries = if (codes.all { it in selectedCountries }) selectedCountries - codes
                                            else selectedCountries + codes
                    },
                    onSelectAll = {
                        selectedCountries = if (selectedCountries.size == byCountry.size) emptySet()
                                            else byCountry.keys.toSet()
                    },
                    allSelected = selectedCountries.size == byCountry.size && byCountry.isNotEmpty(),
                )
            } else {
                ServerStep(
                    servers = selectedServers,
                    pings = pings,
                    handshakes = handshakes,
                    kept = kept,
                    checked = checked,
                    onToggle = { host ->
                        checked = if (host in checked) checked - host else checked + host
                    },
                    onCheckHealthy = {
                        // Prefers servers that completed a real handshake; only falls back to
                        // the ping when nothing has been verified yet, because a server that
                        // answers a TCP ping and then refuses the session is exactly what this
                        // button must not pick.
                        val verified = selectedServers
                            .filter { handshakes[it.hostName] is SoftEtherProbe.Result.Ok }
                        checked = (verified.ifEmpty {
                            selectedServers.filter { (pings[it.hostName] ?: -1) > 0 }
                        }).map { it.hostName }.toSet()
                    },
                )
            }
        }

        // ---- bottom action bar ------------------------------------------------------
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
        ) {
            AnimatedVisibility(visible = snack != null) {
                Text(
                    text = snack.orEmpty(),
                    color = GreenOk,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceDark)
                        .padding(10.dp),
                )
            }
            // In the bottom bar — a Box overlay, not part of the scrolling Column — so it
            // stays put while the user scrolls the list watching results come in.
            VpnGateSweepStrip()

            Spacer(Modifier.height(8.dp))

            if (!showServers) {
                Button(
                    onClick = { checked = emptySet(); showServers = true },
                    enabled = selectedCountries.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary, disabledContainerColor = SurfaceDark),
                ) {
                    Text(
                        text = if (selectedCountries.isEmpty()) "یک کشور انتخاب کنید"
                               else "نمایش سرورها (${selectedCountries.size} کشور)",
                        color = if (selectedCountries.isEmpty()) TextDim else BgDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Filled, not outlined: an OutlinedButton on this dark background drew
                    // essentially nothing and the control looked like floating text.
                    Button(
                        onClick = {
                            if (pinging || VpnGateSweep.isRunning()) return@Button
                            val batch = selectedServers
                            pinging = true
                            // LAZY so begin() is guaranteed to run before the body can finish
                            // and call end() — otherwise a short sweep could clear the strip
                            // before it was ever registered, leaving it stuck on screen.
                            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                                try {
                                    VpnGatePinger.pingAll(
                                        servers = batch,
                                        ovpnOf = { repo.ovpnTextOf(it) },
                                        onResult = { s, rtt ->
                                            VpnGateStore.putPing(s.hostName, rtt)
                                            VpnGateSweep.tick()
                                        },
                                    )
                                } finally {
                                    pinging = false
                                    VpnGateSweep.end()
                                }
                            }
                            VpnGateSweep.begin(VpnGateSweep.Kind.PING, batch.size, job)
                            job.start()
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                    ) {
                        Icon(Icons.Default.NetworkPing, contentDescription = null, tint = Primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (pinging) "در حال تست…" else "پینگ", color = Primary, fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            if (probing || VpnGateSweep.isRunning()) return@Button
                            // Snapshot the list. selectedServers is remember(..., handshakes)
                            // and re-sorts on every result that lands, so reading it inside
                            // the sweep would probe a target set that reshuffles underfoot.
                            val batch = selectedServers
                            probing = true
                            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                                try {
                                    SoftEtherProbe.probeAll(batch) { s, r ->
                                        VpnGateStore.putHandshake(s.hostName, r)
                                        VpnGateSweep.tick()
                                    }
                                } finally {
                                    probing = false
                                    VpnGateSweep.end()
                                }
                            }
                            VpnGateSweep.begin(VpnGateSweep.Kind.PROBE, batch.size, job)
                            job.start()
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                    ) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = GreenOk, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        // Just the state here; the count and percentage live in the progress
                        // strip above, which doesn't scroll away.
                        Text(if (probing) "در حال تست…" else "تست واقعی", color = GreenOk, fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            VpnGatePool.keep(context, checked)
                            snack = "${checked.size} سرور به لیست اصلی اضافه شد"
                            checked = emptySet()
                        },
                        enabled = checked.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GreenOk, disabledContainerColor = SurfaceDark),
                    ) {
                        Text(
                            text = if (checked.isEmpty()) "افزودن" else "افزودن (${checked.size})",
                            color = if (checked.isEmpty()) TextDim else BgDark,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                    }
                }

                // Second row, and only once a real test has actually condemned something.
                // Selecting a continent archives hundreds of servers that a probe then proves
                // dead; without this they accumulate in pool.json forever and push live ones
                // out through MAX_POOL eviction.
                // Counts both result kinds, same rule as the main list's picker: whichever
                // test the user ran is the one holding the evidence. Never the untested.
                val dead = remember(selectedServers, pings, handshakes) {
                    selectedServers.filter {
                        handshakes[it.hostName] is SoftEtherProbe.Result.Failed ||
                            (pings[it.hostName] ?: 0) < 0
                    }.map { it.hostName }
                }
                AnimatedVisibility(visible = dead.isNotEmpty() && sweep == null) {
                    Button(
                        onClick = {
                            val doomed = dead
                            scope.launch {
                                val n = VpnGatePool.purge(context, doomed)
                                VpnGateStore.forgetHandshakes(doomed)
                                checked = checked - doomed.toSet()
                                snack = "$n سرور قطع از آرشیو حذف شد"
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RedError),
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        // The count is in the label on purpose: this deletes in bulk with one
                        // tap, exactly as asked, so the tap must not be a blind one.
                        Text("حذف سرورهای قطع (${dead.size})", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// =============================================================================================
// Step 1 — countries
// =============================================================================================

@Composable
private fun CountryStep(
    byContinent: Map<VpnGateGeo.Continent, List<String>>,
    byCountry: Map<String, List<VpnGateServer>>,
    selected: Set<String>,
    onToggleCountry: (String) -> Unit,
    onToggleContinent: (Set<String>) -> Unit,
    onSelectAll: () -> Unit,
    allSelected: Boolean,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { HowItWorksCard(total = byCountry.values.sumOf { it.size }, countries = byCountry.size) }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (allSelected) Primary.copy(alpha = 0.14f) else SurfaceDark)
                    .clickable(onClick = onSelectAll)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Icon(
                    imageVector = if (allSelected) Icons.Default.CheckCircle else Icons.Default.Public,
                    contentDescription = null,
                    tint = if (allSelected) Primary else TextMuted,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (allSelected) "لغو انتخاب همه" else "انتخاب همهٔ کشورها",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        byContinent.forEach { (continent, codes) ->
            val allInContinent = codes.all { it in selected }
            item(key = "h_${continent.name}") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                ) {
                    Text(continent.emoji, fontSize = 15.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = continent.label,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("(${codes.size})", color = TextDim, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (allInContinent) "لغو قاره" else "انتخاب قاره",
                        color = Primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onToggleContinent(codes.toSet()) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            item(key = "g_${continent.name}") {
                // A nested grid inside a LazyColumn needs a bounded height; rows of 3 at a
                // fixed card height gives an exact one without measuring.
                val rows = (codes.size + 2) / 3
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().height((rows * 78).dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    userScrollEnabled = false,
                ) {
                    items(codes.sortedByDescending { byCountry[it]?.size ?: 0 }, key = { it }) { cc ->
                        CountryCard(
                            code = cc,
                            name = VpnGateGeo.countryName(cc, byCountry[cc]?.firstOrNull()?.countryLong ?: cc),
                            count = byCountry[cc]?.size ?: 0,
                            selected = cc in selected,
                            onClick = { onToggleCountry(cc) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The counter and the how-to, together. Both are needed at once: the number on its own invites
 * the question "why isn't this bigger?", and the answer — VPN Gate only ever publishes about a
 * hundred servers and rotates them — is also the instruction for how to grow it.
 */
@Composable
private fun HowItWorksCard(total: Int, countries: Int) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderDark, RoundedCornerShape(16.dp)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "$total",
                        color = Primary,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "سرور در آرشیو شما",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Text("از $countries کشور", color = TextDim, fontSize = 11.sp)
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.HelpOutline,
                contentDescription = "راهنما",
                tint = Primary,
                modifier = Modifier.size(22.dp),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                Divider(color = BorderDark, thickness = 1.dp)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "شبکه در هر لحظه فقط حدود ۱۰۰ سرور در دسترس می‌گذارد و مدام آن‌ها را " +
                            "عوض می‌کند. این برنامه هر بار که لیست را می‌گیرد، سرورهای تازه را به " +
                            "آرشیو شما اضافه می‌کند و قبلی‌ها را نگه می‌دارد — پس هرچه بیشتر روی " +
                            "دکمهٔ دریافت بزنید، آرشیوتان بزرگ‌تر می‌شود.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(12.dp))
                HelpStep("۱", "کشور یا قاره را انتخاب کنید — می‌توانید چند کشور را با هم بزنید.")
                HelpStep("۲", "دکمهٔ «نمایش سرورها» را بزنید تا سرورهای آن کشورها را ببینید.")
                HelpStep("۳", "«تست پینگ» بگیرید تا تأخیر واقعی هر سرور از گوشی شما اندازه‌گیری شود.")
                HelpStep("۴", "«انتخاب سالم‌ها» و بعد «افزودن» تا به لیست اصلی‌تان اضافه شوند.")
                HelpStep("۵", "در لیست اصلی می‌توانید سرورهای بی‌پاسخ را حذف کنید.")
            }
        }
    }
}

@Composable
private fun HelpStep(number: String, text: String) {
    Row(modifier = Modifier.padding(bottom = 7.dp)) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(number, color = Primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Text(text, color = TextMuted, fontSize = 11.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun CountryCard(
    code: String,
    name: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .height(70.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Primary.copy(alpha = 0.16f) else SurfaceDark)
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) Primary else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
    ) {
        Text(getNodeFlagEmoji(code), fontSize = 20.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            text = name,
            color = if (selected) TextPrimary else TextMuted,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text("$count", color = if (selected) Primary else TextDim, fontSize = 9.sp)
    }
}

// =============================================================================================
// Step 2 — servers
// =============================================================================================

@Composable
private fun ServerStep(
    servers: List<VpnGateServer>,
    pings: Map<String, Int>,
    handshakes: Map<String, SoftEtherProbe.Result>,
    kept: Set<String>,
    checked: Set<String>,
    onToggle: (String) -> Unit,
    onCheckHealthy: () -> Unit,
) {
    // "Verified" counts servers that completed a real handshake; it falls back to the ping
    // count only while no real test has been run yet.
    val verified = servers.count { handshakes[it.hostName] is SoftEtherProbe.Result.Ok }
    val healthy = servers.count { (pings[it.hostName] ?: -1) > 0 }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                text = when {
                    verified > 0 -> "$verified سرور تأییدشده"
                    healthy > 0 -> "$healthy سرور پاسخ‌گو"
                    else -> "هنوز تست نشده"
                },
                color = if (verified > 0 || healthy > 0) GreenOk else TextDim,
                fontSize = 12.sp,
            )
            Spacer(Modifier.weight(1f))
            if (healthy > 0) {
                Text(
                    text = "انتخاب سالم‌ها",
                    color = Primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onCheckHealthy)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(servers, key = { it.hostName }) { server ->
                BrowseServerRow(
                    server = server,
                    ping = pings[server.hostName],
                    handshake = handshakes[server.hostName],
                    alreadyKept = server.hostName in kept,
                    checked = server.hostName in checked,
                    onToggle = { onToggle(server.hostName) },
                )
            }
        }
    }
}

@Composable
private fun BrowseServerRow(
    server: VpnGateServer,
    ping: Int?,
    handshake: SoftEtherProbe.Result?,
    alreadyKept: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) Primary.copy(alpha = 0.12f) else SurfaceDark)
            .clickable(enabled = !alreadyKept, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = when {
                alreadyKept -> Icons.Default.Bookmark
                checked -> Icons.Default.CheckCircle
                else -> Icons.Default.RadioButtonUnchecked
            },
            contentDescription = null,
            tint = when {
                alreadyKept -> GreenOk
                checked -> Primary
                else -> TextDim
            },
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(getNodeFlagEmoji(server.countryShort), fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.hostName,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                // Never the IP — a screenshot of this list shouldn't hand over the endpoints.
                text = if (alreadyKept) "در لیست اصلی" else server.hostName,
                color = if (alreadyKept) GreenOk else TextDim,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
        handshake?.also {
            HandshakeText(it)
            Spacer(Modifier.width(8.dp))
        }
        PingText(ping)
    }
}

/** Real-handshake outcome, beside the ping rather than instead of it. */
@Composable
private fun HandshakeText(result: SoftEtherProbe.Result) {
    val (label, color) = when (result) {
        // Last digit dropped by request: the raw handshake cost runs to four digits (1834,
        // 1894) and crowded the row. Truncating rather than rounding, so the displayed value
        // never reads better than the measurement. Note this is no longer milliseconds —
        // it is a rank/compactness figure, and the smaller number is still the better server.
        is SoftEtherProbe.Result.Ok -> "✓ ${result.ms / 10}" to GreenOk
        is SoftEtherProbe.Result.Failed -> when (result.reason) {
            SoftEtherProbe.Failure.UNREACHABLE -> "بسته" to RedError
            SoftEtherProbe.Failure.TLS_BLOCKED -> "TLS" to RedError
            SoftEtherProbe.Failure.NOT_SOFTETHER -> "غیرفعال" to YellowWarn
            SoftEtherProbe.Failure.REFUSED -> "رد شد" to RedError
            SoftEtherProbe.Failure.TIMEOUT -> "بی‌پاسخ" to RedError
        }
    }
    Text(
        text = label,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
internal fun PingText(ping: Int?) {
    val text = when {
        ping == null -> "—"
        ping > 0 -> "$ping ms"
        else -> "بی‌پاسخ"
    }
    val color = when {
        ping == null -> TextDim
        ping <= 0 -> RedError
        ping < 150 -> GreenOk
        ping < 400 -> YellowWarn
        else -> RedError
    }
    Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}
