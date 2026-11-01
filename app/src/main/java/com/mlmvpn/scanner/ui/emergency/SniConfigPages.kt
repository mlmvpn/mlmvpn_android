package com.mlmvpn.scanner.ui.emergency

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.filled.Sort
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.engines.rstaspoof.SniSession
import com.mlmvpn.scanner.models.VpnNode
import androidx.compose.foundation.layout.Arrangement
import com.mlmvpn.scanner.ui.SNI_GROUP_NAME
import com.mlmvpn.scanner.ui.ToolbarAction
import com.mlmvpn.scanner.ui.theme.PanelShape
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.spoofToSni
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Pick which SNI config the dial connects.
 *
 * Shows the measured delay against each one when a measurement has been run, because that is the
 * only reason to open this page rather than let the measurement adopt the fastest by itself.
 */
/** Rows of the SNI config list composed at a time; see SniConfigPickerPage. */
private const val SNI_PAGE = 150

@Composable
internal fun SniConfigPickerPage(
    configs: List<VpnNode>,
    selectedId: String?,
    delays: Map<String, Long>,
    measuring: Boolean,
    measuredLabel: String?,
    onMeasure: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (VpnNode) -> Unit,
    onDeleteDead: () -> Unit,
    onDeleteAll: () -> Unit,
    onBack: () -> Unit,
) {
    // One latch, shared by the two bulk buttons, so arming one disarms the other. They sit a
    // thumb's width apart, and an armed "are you sure" left standing on the button you did NOT
    // mean to press is how a confirm step becomes the thing it was meant to prevent.
    var confirm by remember { mutableStateOf<String?>(null) }
    // Which row is swiped open. One at a time, like every list that does this.
    var swiped by remember { mutableStateOf<String?>(null) }
    // Fastest first by default, so a measurement puts the answer at the top without being asked.
    var sortByDelay by remember { mutableStateOf(true) }

    // Then the ones that answered nothing, then the unmeasured -- a config with no reading is not
    // a slow config, it has not been asked, and sinking it below the failures would say it was.
    // With no readings at all every key is equal and the sort is stable, so the list keeps the
    // order it came in.
    //
    // Deliberately NOT wrapped in `remember`. The readings arrive in a snapshot map that is the
    // same INSTANCE from first composition to last, so a `remember(delays)` key never changes and
    // the order computed on the first frame is the order you keep -- the numbers appeared live
    // while the list they were supposed to be sorting stood still. Sorting a few hundred rows on
    // each recomposition costs nothing next to being wrong.
    val shown = if (!sortByDelay) configs else configs.sortedBy { node ->
        val ms = delays[node.id]
        when {
            ms == null -> Long.MAX_VALUE - 1
            ms <= 0L -> Long.MAX_VALUE
            else -> ms
        }
    }
    val deadCount = configs.count { (delays[it.id] ?: 1L) <= 0L }

    IosScreen(
        title = stringResource(R.string.sni_row_selected_config),
        onBack = onBack,
        backLabel = stringResource(R.string.emergency_3_title),
    ) {
        Spacer(Modifier.height(10.dp))

        // The same toolbar the connection screen wears, for the same reason: this is a list of
        // configs, and everything anyone wants to do with a list of configs is measure it, order
        // it and take things out of it. It used to be a list you could only pick from.
        //
        // The two destructive ones say "delete" in full. A four-character label over a bin icon
        // is only obvious to whoever wrote it, and these two are the buttons where being wrong
        // about what they do costs the most.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .frostedGlass(PanelShape)
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarAction(
                icon = Icons.Default.AccessTime,
                label = measuredLabel ?: stringResource(R.string.delay),
                enabled = configs.isNotEmpty() && !measuring,
                onClick = { confirm = null; swiped = null; onMeasure() },
            )
            ToolbarAction(
                icon = Icons.Default.Sort,
                label = stringResource(R.string.sort),
                active = sortByDelay,
                onClick = { confirm = null; swiped = null; sortByDelay = !sortByDelay },
            )
            ToolbarAction(
                icon = Icons.Default.HeartBroken,
                label = if (confirm == "dead") stringResource(R.string.sni_tap_again)
                else stringResource(R.string.sni_delete_dead_short),
                enabled = deadCount > 0,
                onClick = {
                    swiped = null
                    if (confirm == "dead") {
                        confirm = null
                        onDeleteDead()
                    } else {
                        confirm = "dead"
                    }
                },
            )
            ToolbarAction(
                icon = Icons.Default.DeleteSweep,
                label = if (confirm == "all") stringResource(R.string.sni_tap_again)
                else stringResource(R.string.sni_delete_all_short),
                enabled = configs.isNotEmpty(),
                onClick = {
                    swiped = null
                    if (confirm == "all") {
                        confirm = null
                        onDeleteAll()
                    } else {
                        confirm = "all"
                    }
                },
            )
        }

        Spacer(Modifier.height(14.dp))
        // A page of rows at a time. Every row was composed at once -- a swipe row, a text layout and
        // a delay badge each -- and a phone holding a few thousand SNI configs froze on this page
        // and then ran out of memory on it (freeze and OOM reports, 1.2.41). Sorted fastest first,
        // the rows that matter are on the first page anyway.
        var limit by remember { mutableStateOf(SNI_PAGE) }
        SettingsGroup {
            shown.take(limit).forEachIndexed { index, node ->
                if (index > 0) Separator()
                SwipeToDeleteRow(
                    revealed = swiped == node.id,
                    onReveal = { swiped = if (it) node.id else null },
                    onDelete = { swiped = null; onDelete(node) },
                ) {
                    SniConfigRow(
                        node = node,
                        selected = node.id == selectedId,
                        delay = delays[node.id],
                        // An open row closes on a tap rather than selecting. Otherwise the
                        // gesture has no way back except another swipe, and a thumb that lands
                        // slightly wide of the bin picks the config instead of deleting it --
                        // which is the one mistake this row must not make easy.
                        onClick = {
                            confirm = null
                            if (swiped == node.id) {
                                swiped = null
                            } else {
                                swiped = null
                                onSelect(node.id)
                            }
                        },
                    )
                }
            }
        }
        if (shown.size > limit) {
            Spacer(Modifier.height(10.dp))
            SettingsGroup {
                SettingsActionRow(
                    label = com.mlmvpn.scanner.store.tr(
                        "نمایش ${faDigits(minOf(SNI_PAGE, shown.size - limit).toString())} مورد بعدی (${faDigits((shown.size - limit).toString())} مانده)",
                        "Show ${minOf(SNI_PAGE, shown.size - limit)} more (${shown.size - limit} left)",
                    ),
                    icon = Icons.Default.Sort,
                    tint = Ios.Blue,
                    onClick = { limit += SNI_PAGE },
                )
            }
        }
        SettingsFooter(stringResource(R.string.sni_picker_footer))
        Spacer(Modifier.height(10.dp))
        SettingsFooter(stringResource(R.string.sni_delete_footer))

        Spacer(Modifier.height(28.dp))
    }
}

/**
 * Drag a row aside to uncover a bin, the way a phone's call list does it.
 *
 * Deleting ONE thing belongs on the thing, not in a toolbar: a toolbar button has to act on "the
 * selected one", which means selecting a config in order to delete it -- and selecting is also how
 * you choose what to connect, so the two get confused exactly when it matters.
 *
 * Three things this got wrong first, all of them visible on the phone:
 *
 * **The row is NARROWED, not slid.** An offset moves a row without telling the layout, so the row
 * kept its full width, kept drawing over the space it had supposedly left, and the bin showed
 * through it. Giving the bin real width in the same row means the two can never overlap however
 * far the drag goes.
 *
 * **The name went the wrong way.** The bin belongs on the side the row leaves -- the physical
 * right in a right-to-left layout -- so the child ORDER is chosen from the layout direction and
 * not from an alignment: in an RTL row the first child is the rightmost one. Put the other way
 * round, the row grew from the left and the name slid right, which is the opposite of the gesture.
 *
 * **It stuttered.** The travel was fed through `animateFloatAsState`, so every finger position was
 * a new animation target and the row chased the finger a frame or two behind, in steps. An
 * `Animatable` is snapped to the finger while the finger is down -- one to one, no interpolation --
 * and only animated when it is lifted, on a spring with no bounce, so it settles the same whether
 * it is opening or going back.
 */
@Composable
private fun SwipeToDeleteRow(
    revealed: Boolean,
    onReveal: (Boolean) -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val revealPx = with(density) { REVEAL_WIDTH.toPx() }
    val travel = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    val settle = remember {
        spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    }

    // Another row opening closes this one, and the close is animated like any other.
    LaunchedEffect(revealed) {
        if (!dragging) travel.animateTo(if (revealed) -revealPx else 0f, settle)
    }

    val binWidth = with(density) { (-travel.value).coerceAtLeast(0f).toDp() }
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current ==
        androidx.compose.ui.unit.LayoutDirection.Rtl

    @Composable
    fun Bin() {
        if (binWidth <= 0.dp) return
        Box(
            modifier = Modifier
                .width(binWidth)
                .heightIn(min = 44.dp)
                .background(Ios.Red)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            // Held back until the gap can hold it, so the glyph is never drawn squashed against
            // an edge on the way open.
            if (binWidth > 44.dp) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        // Past a third of the way is a decision; short of it is a nudge, and a
                        // nudge goes back exactly as smoothly as it came.
                        val open = travel.value < -revealPx / 3f
                        onReveal(open)
                        scope.launch { travel.animateTo(if (open) -revealPx else 0f, settle) }
                    },
                    onDragCancel = {
                        dragging = false
                        scope.launch { travel.animateTo(0f, settle) }
                    },
                ) { change, delta ->
                    change.consume()
                    scope.launch {
                        travel.snapTo((travel.value + delta).coerceIn(-revealPx, 0f))
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rtl) Bin()
        Box(modifier = Modifier.weight(1f)) { content() }
        if (!rtl) Bin()
    }
}

/** How much of the row the bin takes when it is uncovered. */
private val REVEAL_WIDTH = 76.dp

@Composable
private fun SniConfigRow(
    node: VpnNode,
    selected: Boolean,
    delay: Long?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                node.name.ifBlank { node.type },
                color = Ios.Label,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                node.groupTitle?.takeIf { it.isNotBlank() } ?: node.type,
                color = Ios.SecondaryLabel,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (delay != null) {
            Text(
                if (delay > 0) faDigits(delay.toString()) + "ms"
                else stringResource(R.string.emergency_3_no_ping),
                color = if (delay > 0) pingTone(delay.toInt()) else Ios.Red,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(10.dp))
        }

        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Ios.Blue,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Build SNI configs out of the configs the user already has.
 *
 * The same conversion the "+ Add" menu on the connection page offers -- [spoofToSni], which points
 * a config at the local TLS front and forces `allowInsecure`. It lives here as well because this
 * is the screen where it is needed: the dial has nothing to connect until it has been run once,
 * and a first-run requirement hidden in another screen's "+" menu is one nobody finds.
 *
 * The originals are untouched. Conversion produces new nodes in their own folder.
 */
@Composable
internal fun SniBuildPage(onBuilt: (Int) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    // Read from the two lists as they ARRIVE, not from a snapshot taken at first composition.
    //
    // Both stores load on a background thread when they are first created, so a snapshot taken
    // the moment this page opened could see an empty list and answer «no cloud config to convert»
    // over a phone full of them. And the configs a panel hands out in Cloud live in Cloud's own
    // groups, not in the connection list -- a user who had just pressed «دریافت کانفیگ‌ها» on BPB
    // was told there was nothing to build from (device, 2026-10-05).
    val nodeStore = remember { NodeManager(context) }
    val listed by nodeStore.nodesFlow.collectAsState()
    val cloudGroups by com.mlmvpn.scanner.data.GroupManager(context).cloudGroupsFlow.collectAsState()
    val buckets = remember(listed, cloudGroups) {
        // Cloud panel configs only -- BPB, EDG, Nahan, MLM, and the combined configs built from
        // them. See [SniSession.isConvertible] for why that is not a preference: the front dials
        // a Cloudflare edge, and only a config whose own server sits behind that edge can be
        // reached through it. Everything else came out of the build as a config that could never
        // connect, and a list full of those is worse than a short list.
        //
        // Protected Iran defaults and the SNI configs themselves are excluded by the same call --
        // the latter already point at the front, and converting one would aim it at itself.
        val seen = HashSet<String>()
        val fromList = listed
            .filter { SniSession.isConvertible(it) && seen.add(it.uri) }
            .groupBy { it.groupTitle }
            .map { (g, ns) -> (g ?: S(R.string.default_str_r2)) to ns }
        // A Cloud group is a panel's own output, so its configs qualify when they are TLS to a
        // worker -- the same test the connect path uses to call a config Cloudflare-fronted.
        val fromCloud = cloudGroups.mapNotNull { g ->
            val ns = g.nodes.filter { n ->
                !SniSession.isSniNode(n) && seen.add(n.uri) &&
                    com.mlmvpn.scanner.utils.VpnConfig.parseUri(n.uri)
                        ?.let { com.mlmvpn.scanner.utils.CfUnfilter.isCloudflareFronted(it) } == true
            }
            ns.takeIf { it.isNotEmpty() }?.let { g.title to it }
        }
        fromList + fromCloud
    }
    val allNodes = remember(buckets) { buckets.flatMap { it.second } }
    var selectedIdx by remember { mutableStateOf(-1) }

    IosScreen(
        title = stringResource(R.string.sni_build_configs),
        onBack = onBack,
        backLabel = stringResource(R.string.emergency_3_title),
    ) {
        Spacer(Modifier.height(14.dp))

        if (buckets.isEmpty()) {
            // Says WHICH configs are missing, not just that some are. "No config found" over a
            // connection list with forty configs in it reads as a bug rather than as a rule.
            SettingsFooter(S(R.string.sni_no_cloud_config))
            Spacer(Modifier.height(28.dp))
            return@IosScreen
        }

        SettingsSectionHeader(S(R.string.which_configs_should_be_converted))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.all_r2),
                value = faDigits(allNodes.size.toString()),
                showChevron = false,
                icon = if (selectedIdx == -1) Icons.Default.Check else null,
                tint = Ios.Blue,
                onClick = { selectedIdx = -1 },
            )
            buckets.forEachIndexed { index, (label, nodes) ->
                Separator()
                SettingsRow(
                    title = label,
                    value = faDigits(nodes.size.toString()),
                    showChevron = false,
                    icon = if (selectedIdx == index) Icons.Default.Check else null,
                    tint = Ios.Blue,
                    onClick = { selectedIdx = index },
                )
            }
        }
        SettingsFooter(stringResource(R.string.sni_build_footer))

        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            SettingsActionRow(
                label = S(R.string.build_and_add),
                icon = Icons.Default.Check,
                tint = Ios.Green,
            ) {
                val source = if (selectedIdx == -1) {
                    allNodes
                } else {
                    buckets.getOrNull(selectedIdx)?.second ?: emptyList()
                }
                val nm = NodeManager(context)
                val existing = synchronized(nm.nodes) { nm.nodes.map { it.uri }.toSet() }
                val converted = source.mapNotNull { node ->
                    val spoofed = spoofToSni(node.uri) ?: return@mapNotNull null
                    // Skip one that already exists. Running the build twice used to produce a
                    // second identical copy of every config, and the list is the thing the dial
                    // picks from.
                    if (spoofed in existing) return@mapNotNull null
                    node.copy(
                        id = UUID.randomUUID().toString(),
                        uri = spoofed,
                        groupTitle = SNI_GROUP_NAME,
                    )
                }
                if (converted.isNotEmpty()) {
                    synchronized(nm.nodes) { nm.nodes.addAll(0, converted) }
                    nm.saveNodes()
                }
                onBuilt(converted.size)
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}
