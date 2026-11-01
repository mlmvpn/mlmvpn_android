package com.mlmvpn.scanner.ui.configstudio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.api.DeviceReport
import com.mlmvpn.scanner.data.studio.api.StudioDevice
import com.mlmvpn.scanner.data.studio.api.RenewMode
import com.mlmvpn.scanner.data.studio.domain.ActivitySeverity
import com.mlmvpn.scanner.data.studio.api.SessionEntry
import com.mlmvpn.scanner.data.studio.domain.ActivityEntry
import com.mlmvpn.scanner.data.studio.api.StudioHttpApi
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.data.studio.domain.Enforcement
import com.mlmvpn.scanner.data.studio.domain.StoppedReason
import com.mlmvpn.scanner.data.studio.domain.StudioUser
import com.mlmvpn.scanner.data.studio.domain.Subscription
import com.mlmvpn.scanner.data.studio.domain.ExitCountries
import com.mlmvpn.scanner.ui.configstudio.design.NoticeCard
import com.mlmvpn.scanner.ui.configstudio.design.StatusLine
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.design.StudioSeparator
import com.mlmvpn.scanner.ui.configstudio.design.StudioType
import com.mlmvpn.scanner.ui.configstudio.parts.FactsCard
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.PayloadCard
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.StudioChip
import com.mlmvpn.scanner.ui.configstudio.parts.StudioQrCard
import com.mlmvpn.scanner.ui.configstudio.parts.StudioShare
import com.mlmvpn.scanner.ui.configstudio.parts.WizardBusy
import com.mlmvpn.scanner.ui.configstudio.parts.WizardField
import com.mlmvpn.scanner.ui.configstudio.parts.WizardPrimary
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.launch

/**
 * One user: what they have, and the three things done to them most often.
 *
 * **Stale for browsing, fresh for acting.** The screen paints immediately from the local index, then
 * re-reads from the engine — so it is never a spinner, and never acts on figures the operator has
 * been looking at for a while. Every mutation here goes through `StudioStore`, which re-reads the
 * user before writing; nothing calls the API with a `StudioUser` taken from the index.
 *
 * The two destructive actions ask first, and each says what actually happens rather than "are you
 * sure": rotating cuts off whoever is using the current link, and deleting cannot be undone.
 */
@Composable
fun StudioUserDetailScreen(
    store: StudioStore,
    installationId: String,
    userId: String,
    onOpenConfigs: (username: String) -> Unit,
    /** «لوکیشن‌ها و پورت‌ها» for this person (build 17). */
    onEditLocations: (username: String) -> Unit = {},
    /** Opens «ترکیب با آی‌پی تمیز» for this person. Named apart from the config list because it is
     *  a different question: not "what do they have" but "why can they not connect". */
    onCombine: (username: String) -> Unit,
    onBack: () -> Unit,
    /** This person was moved to a central Worker: their new page there. */
    onMoved: (installationId: String, userId: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var user by remember { mutableStateOf<StudioUser?>(store.cached(installationId, userId)) }
    var sub by remember { mutableStateOf<Subscription?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    var renewing by remember { mutableStateOf(false) }
    var addDays by remember { mutableStateOf("") }
    var addGb by remember { mutableStateOf("") }

    /**
     * Whether the two figures below are being given or taken back.
     *
     * One pair of fields and a direction, rather than four fields. «کم کردن روز» beside
     * «افزودن روز» is two boxes that must never both be filled in, which is a rule the screen
     * would have to explain and the operator would have to remember.
     */
    var reducing by remember { mutableStateOf(false) }
    var confirmResetUsage by remember { mutableStateOf(false) }
    var confirmRotate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmMove by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<DeviceReport?>(null) }
    var history by remember { mutableStateOf<List<SessionEntry>>(emptyList()) }
    var userActivity by remember { mutableStateOf<List<ActivityEntry>>(emptyList()) }
    var loadingHistory by remember { mutableStateOf(true) }
    var deviceMenu by remember { mutableStateOf<StudioDevice?>(null) }

    // Edited locally and written on Save, not on every keystroke: a PATCH per character would be a
    // request per character, and on a shared D1 that is the write budget spent on typing.
    //
    // Seeded from the index rather than left null, so the "has this changed" test below compares
    // two real values from the first frame. Left null it compares an empty box against a note that
    // exists, decides the operator has cleared it, and offers a Save that would.
    var noteDraft by remember { mutableStateOf(user?.note.orEmpty()) }
    var tagsDraft by remember { mutableStateOf(user?.tags?.joinToString("، ").orEmpty()) }

    /** Whether the fields have been touched. Until they have, a reload may overwrite them. */
    var labelsTouched by remember { mutableStateOf(false) }

    val account = remember { store.installedAccounts().firstOrNull { it.id == installationId } }

    /**
     * Whether THIS installation's engine understands a renewal that takes something back.
     *
     * Asked rather than assumed, and per installation rather than globally, for the reason R13
     * gives: with an uncapped fleet, one account on build 7 while another is on build 8 is an
     * ordinary state. Build 7 takes `add_days: -10` and pushes the expiry ten days into the PAST
     * unclamped, and reads `mode: 'reset_usage'` as `extend` — then refuses it for carrying no
     * figure. Both failures are silent, so the controls are simply not drawn where they would lie.
     */
    val canAdjust = store.state.collectAsState().value.installations
        .firstOrNull { it.installationId == installationId }
        ?.can("renew.adjust") == true

    /**
     * Whether the SAME link opens as a status page when a person taps it in a browser.
     *
     * Build 11. Worth asking rather than assuming, because it changes what the operator should say
     * when they hand the link over: on an older engine a person who taps it gets a wall of text and
     * concludes it is broken, and telling them otherwise makes that worse rather than better.
     */
    val canPage = store.state.collectAsState().value.installations
        .firstOrNull { it.installationId == installationId }
        ?.can("sub.page") == true

    /**
     * Whether this installation records what a person actually did.
     *
     * Build 12. `sessions` was created by the third migration and nothing had ever written to it,
     * so an older engine answers the history endpoint with an empty list — which on screen is
     * indistinguishable from somebody who has never connected. That is the one reading an operator
     * must not be given, so the whole section is absent instead.
     */
    val canSessions = store.state.collectAsState().value.installations
        .firstOrNull { it.installationId == installationId }
        ?.can("sessions.v1") == true

    suspend fun reload() {
        val acc = account ?: return
        when (val fresh = StudioHttpApi(context, acc).getUser(userId)) {
            is StudioResult.Ok -> {
                user = fresh.value
                // Written back into the local index, so the list this page was opened from shows the
                // same figures when the operator goes back -- not the ones from the last full sync.
                store.remember(installationId, fresh.value)
                error = null
                // Re-seeded from the engine, but only while the operator has not started typing:
                // overwriting a half-typed note with a background reload is the kind of loss that
                // is never noticed until the note mattered.
                if (!labelsTouched) {
                    noteDraft = fresh.value.note.orEmpty()
                    tagsDraft = fresh.value.tags.joinToString("، ")
                }
            }
            is StudioResult.Err -> error = messageForDetail(context, fresh.error.code)
        }
        when (val s = StudioHttpApi(context, acc).getSubscription(userId)) {
            is StudioResult.Ok -> sub = s.value
            is StudioResult.Err -> Unit // a user may legitimately have none yet
        }
        // Asked of the Durable Object that enforces the cap, so the number here and the number
        // doing the refusing cannot disagree. An installation without one answers `available:
        // false`, which is a third state and not an empty list — "nobody has connected" and "this
        // installation cannot count" would otherwise look identical.
        when (val d = StudioHttpApi(context, acc).listDevices(userId)) {
            is StudioResult.Ok -> devices = d.value
            // A request that failed says nothing about the installation. Before build 18 it was
            // drawn as "limits recorded but not applied", which on a working account was false.
            is StudioResult.Err -> devices = DeviceReport(available = false, reason = DeviceReport.UNREACHABLE)
        }
        // Last, and only when the engine can answer: this pair is the bottom of the screen, and
        // making the top of it wait on two more requests would turn a page that paints instantly
        // into one that does not.
        if (canSessions) {
            loadingHistory = true
            history = StudioHttpApi(context, acc).userSessions(userId).valueOrNull.orEmpty()
            userActivity = StudioHttpApi(context, acc)
                .activity(userId = userId, limit = 8).valueOrNull?.items.orEmpty()
            loadingHistory = false
        } else {
            loadingHistory = false
        }
    }

    LaunchedEffect(installationId, userId) { reload() }

    val u = user
    IosScreen(
        title = u?.username ?: S(R.string.studio_users),
        onBack = if (busy) null else onBack,
        backLabel = S(R.string.studio_users),
        onRefresh = { reload() },
    ) {
        if (u == null || account == null) {
            InfoCard(S(R.string.studio_err_generic))
            return@IosScreen
        }

        Spacer(Modifier.height(10.dp))
        note?.let { InfoCard(it); Spacer(Modifier.height(12.dp)) }
        error?.let { ProblemCard(it); Spacer(Modifier.height(12.dp)) }

        u.stoppedReason()?.let { reason ->
            ProblemCard(
                when (reason) {
                    StoppedReason.DISABLED -> S(R.string.studio_row_disabled)
                    StoppedReason.EXPIRED -> S(R.string.studio_row_expired)
                    StoppedReason.OUT_OF_VOLUME -> S(R.string.studio_row_out_of_volume)
                    StoppedReason.DELETED -> S(R.string.studio_row_expired)
                }
            )
            Spacer(Modifier.height(14.dp))
        }

        // ---- volume ------------------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_section_volume))
        // Rows, not a footer stuffed into a card. A footer is the grey paragraph iOS puts UNDER
        // a group -- 32dp side insets and no bottom padding at all -- so inside one it drew text
        // indented past the rows above it and pressed flat against the card's bottom edge.
        val quota = u.policy.quotaBytes
        if (quota == null) {
            FactsCard(S(R.string.studio_unlimited_volume))
        } else {
            SettingsGroup {
                SettingsRow(
                    title = S(R.string.studio_used_of)
                        .replace("%1\$s", bytesFa(u.usage.usedBytes))
                        .replace("%2\$s", bytesFa(quota)),
                    showChevron = false,
                )
                Separator()
                SettingsRow(
                    title = S(R.string.studio_remaining),
                    value = bytesFa(u.usage.remainingOf(quota) ?: 0),
                    showChevron = false,
                )
            }
        }

        // ---- time --------------------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_section_time))
        val expiresAt = u.policy.expiresAt
        if (expiresAt == null) {
            FactsCard(S(R.string.studio_no_end_date))
        } else {
            SettingsGroup {
                SettingsRow(
                    title = S(R.string.studio_time_left),
                    // Hours on the last day, not «expired» while half a day is still left.
                    value = timeLeftText(expiresAt) ?: S(R.string.studio_row_expired),
                    showChevron = false,
                )
            }
        }

        // ---- the link ----------------------------------------------------------------
        sub?.let { s ->
            SettingsSectionHeader(S(R.string.studio_section_link))
            PayloadCard(s.url)
            // One link, two audiences: a client fetching it gets the servers, a person tapping it
            // gets a page that says how much is left and why something stopped. Said here because
            // it changes what the operator writes in the message they paste it into.
            if (canPage) InfoCard(S(R.string.studio_sub_page_note))
            Spacer(Modifier.height(10.dp))
            SettingsGroup {
                SettingsActionRow(
                    label = S(R.string.studio_copy_link),
                    icon = StudioIcons.Copy,
                    tint = Ios.Blue,
                ) {
                    clipboard.setText(AnnotatedString(s.url))
                    note = S_copied(context)
                }
                Separator()
                // The clipboard was the only way out of this screen until now, which works when the
                // operator is already standing in the chat they mean to paste into and not
                // otherwise. These are the two other ways a link crosses to somebody: a camera, and
                // whatever they happen to have installed.
                SettingsActionRow(
                    label = S(R.string.studio_share),
                    icon = StudioIcons.Share,
                    tint = Ios.Blue,
                ) {
                    StudioShare.shareText(
                        context, s.url,
                        S_shareSubject(context).replace("%1\$s", u.username),
                    )
                }
                Separator()
                SettingsActionRow(
                    label = if (showQr) S(R.string.studio_qr_hide) else S(R.string.studio_qr),
                    icon = StudioIcons.Qr,
                    tint = Ios.Indigo,
                ) { showQr = !showQr }
                Separator()
                SettingsActionRow(
                    label = S(R.string.studio_export_file),
                    icon = StudioIcons.Export,
                    tint = Ios.Green,
                ) {
                    // The link plus who it belongs to. A bare URL in a text file is something
                    // nobody can identify a week later, and identifying it is the whole reason to
                    // save one rather than paste it.
                    val ok = StudioShare.shareFile(
                        context,
                        fileName = u.username + ".txt",
                        content = u.username + "\n" + s.url + "\n",
                        subject = S_shareSubject(context).replace("%1\$s", u.username),
                    )
                    if (!ok) error = S_exportFailed(context) else note = S_exported(context)
                }
                if (canPage) {
                    Separator()
                    // Opened rather than described. An operator who has seen the page once knows
                    // what the person on the other end is looking at, which is the difference
                    // between answering "is it working?" and guessing at it.
                    SettingsActionRow(
                        label = S(R.string.studio_sub_page_open),
                        icon = StudioIcons.OpenPage,
                        tint = Ios.Teal,
                    ) {
                        try {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(s.pageUrl.ifBlank { s.url }),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (e: Exception) {
                            error = context.getString(R.string.studio_sub_page_no_browser)
                        }
                    }
                }
                Separator()
                SettingsActionRow(
                    label = S(R.string.studio_rotate),
                    icon = StudioIcons.Credential,
                    tint = Ios.Orange,
                    enabled = !busy,
                ) { confirmRotate = true }
            }
            if (showQr) {
                Spacer(Modifier.height(10.dp))
                StudioQrCard(s.url, S(R.string.studio_qr_too_long))
            }
        }

        // ---- note and tags ---------------------------------------------------------------
        //
        // The columns have existed since schema v6 and nothing has ever written to them, so every
        // installation in the fleet is carrying an empty `note` and an empty `tags` for every
        // person in it. They are here rather than on the create screen because what an operator
        // knows about somebody is learned after handing them a link, not before.
        SettingsSectionHeader(S(R.string.studio_section_labels))
        WizardField(
            value = tagsDraft,
            onValueChange = { tagsDraft = it; labelsTouched = true },
            placeholder = S(R.string.studio_tags_hint),
            ltr = false,
        )
        Spacer(Modifier.height(8.dp))
        WizardField(
            value = noteDraft,
            onValueChange = { noteDraft = it; labelsTouched = true },
            placeholder = S(R.string.studio_note_hint),
            ltr = false,
            lines = 3,
        )
        InfoCard(S(R.string.studio_labels_note))
        // Drawn only when there is something to save. A Save button that is always there is a
        // button whose state says nothing, and on this screen it sits above the destructive
        // actions where an idle tap is worth avoiding.
        if (noteDraft.trim() != u.note.orEmpty() || splitTags(tagsDraft) != u.tags) {
            Spacer(Modifier.height(10.dp))
            WizardPrimary(S(R.string.studio_save), busy = busy) {
                busy = true; error = null
                val newNote = noteDraft.trim()
                val newTags = splitTags(tagsDraft)
                scope.launch {
                    when (val res = store.setLabels(account, userId, newNote, newTags)) {
                        is StudioResult.Ok -> {
                            user = res.value
                            labelsTouched = false
                            note = context.getString(R.string.studio_saved)
                        }
                        is StudioResult.Err -> error = messageForDetail(context, res.error.code)
                    }
                    busy = false
                }
            }
        }

        // ---- devices -----------------------------------------------------------------
        //
        // One promise: **never draw a count this installation cannot measure**, and never call a
        // failed request "not applied". Three states, each said as what it is: could not read (try
        // again), this account has no device counter (update its engine), or the real figures from
        // the Durable Object that does the refusing (build 18: devices connected right now).
        SettingsSectionHeader(S(R.string.studio_section_devices))
        val report = devices
        val limit = u.policy.deviceLimit
        when {
            report == null -> WizardBusy(S(R.string.studio_loading))

            report.failedToRead -> NoticeCard(
                icon = StudioIcons.Warning,
                tint = Ios.Orange,
                title = S(R.string.studio_devices_read_failed_title),
                body = S(R.string.studio_devices_read_failed_body),
            )

            !report.available -> {
                FactsCard(
                    limit?.let { S(R.string.studio_devices_allowed).replace("%1\$s", faNum(it)) }
                        ?: S(R.string.studio_devices_no_limit)
                )
                if (limit != null) {
                    NoticeCard(
                        icon = StudioIcons.Devices,
                        tint = Ios.Orange,
                        title = S(R.string.studio_new_devices_off_title),
                        body = S(R.string.studio_new_devices_off_body),
                    )
                }
            }

            else -> {
                val full = limit != null && report.live >= limit
                SettingsGroup {
                    SettingsRow(
                        title = limit?.let {
                            S(R.string.studio_devices_online_of)
                                .replace("%1\$s", faNum(report.live))
                                .replace("%2\$s", faNum(it))
                        } ?: S(R.string.studio_devices_online_n).replace("%1\$s", faNum(report.live)),
                        subtitle = S(R.string.studio_devices_seen_n).replace("%1\$s", faNum(report.activeCount)),
                        icon = StudioIcons.Devices,
                        tint = if (full) Ios.Orange else Ios.Green,
                        showChevron = false,
                    )
                    // The monitor-only switch, and only where there is a limit for it to switch:
                    // without one, "refuse" and "do not refuse" are two names for the same thing.
                    if (limit != null) {
                        Separator()
                        SettingsToggle(
                            title = S(R.string.studio_enforcement_title),
                            checked = u.policy.enforcement == Enforcement.STRICT,
                            subtitle = S(R.string.studio_enforcement_sub),
                            icon = StudioIcons.Block,
                            tint = Ios.Red,
                            onCheckedChange = { strict ->
                                if (busy) return@SettingsToggle
                                busy = true; error = null
                                scope.launch {
                                    when (val res = store.setEnforcement(account, userId, strict)) {
                                        is StudioResult.Ok -> { user = res.value; note = context.getString(R.string.studio_saved) }
                                        is StudioResult.Err -> error = messageForDetail(context, res.error.code)
                                    }
                                    busy = false
                                }
                            },
                        )
                    }
                }
                // Said every time these are shown: a device is recognised from what the client
                // sends and the network it comes from, which is an estimate and not an identity.
                SettingsFooter(S(R.string.studio_devices_heuristic))

                if (report.devices.isNotEmpty()) {
                    SettingsGroup {
                        report.devices.forEachIndexed { index, device ->
                            if (index > 0) StudioSeparator()
                            DeviceRow(device) { deviceMenu = device }
                        }
                    }
                }
            }
        }

        // ---- renew -------------------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_renew_title))
        if (!renewing) {
            SettingsGroup {
                SettingsActionRow(
                    label = S(R.string.studio_renew),
                    icon = StudioIcons.Renew,
                    tint = Ios.Green,
                    enabled = !busy,
                ) { renewing = true }
            }
        } else {
            InfoCard(if (reducing) S(R.string.studio_renew_reduce_sub) else S(R.string.studio_renew_sub))

            // Give or take back, on one pair of fields. Two pairs would be two boxes that must
            // never both be filled in -- a rule the screen has to explain and the operator has to
            // remember, to save one tap.
            if (canAdjust) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StudioChip(S(R.string.studio_renew_dir_add), !reducing, Modifier.weight(1f)) { reducing = false }
                    StudioChip(S(R.string.studio_renew_dir_sub), reducing, Modifier.weight(1f)) { reducing = true }
                }
            }

            SettingsSectionHeader(
                if (reducing) S(R.string.studio_renew_sub_days) else S(R.string.studio_renew_add_days)
            )
            WizardField(addDays, { addDays = it }, "30")
            SettingsSectionHeader(
                if (reducing) S(R.string.studio_renew_sub_gb) else S(R.string.studio_renew_add_gb)
            )
            WizardField(addGb, { addGb = it }, "30")
            Spacer(Modifier.height(12.dp))
            WizardPrimary(
                if (reducing) S(R.string.studio_renew_reduce) else S(R.string.studio_renew_extend),
                busy = busy,
            ) {
                // `abs` then a sign, so a typed "-5" in the reduce direction does not add five back.
                val sign = if (reducing) -1 else 1
                val days = addDays.trim().toIntOrNull()?.let { kotlin.math.abs(it) * sign }
                val bytes = addGb.trim().toDoubleOrNull()
                    ?.let { (kotlin.math.abs(it) * 1073741824).toLong() * sign }
                if (days == null && bytes == null) {
                    error = S_needsInput(context)
                    return@WizardPrimary
                }
                busy = true; error = null
                scope.launch {
                    // Through the store, so the user is re-read from their own installation before
                    // the write -- the index this screen painted from is minutes old by design.
                    when (val res = store.renew(account, userId, addDays = days, addBytes = bytes)) {
                        is StudioResult.Ok -> {
                            user = res.value
                            note = S_renewed(context)
                            renewing = false; reducing = false; addDays = ""; addGb = ""
                        }
                        is StudioResult.Err -> error = messageForDetail(context, res.error.code)
                    }
                    busy = false
                }
            }
            // Stated, because "start over" and "add to what is there" are not obviously different
            // until one of them has thrown away somebody's remaining volume.
            InfoCard(
                if (reducing) S(R.string.studio_renew_reduce_note)
                else S(R.string.studio_renew_restart_note)
            )

            // Its own action rather than a third direction, because it answers a different
            // question: not "how much should they have" but "the volume they have was spent by
            // something that was not them".
            if (canAdjust) {
                Spacer(Modifier.height(10.dp))
                SettingsGroup {
                    SettingsActionRow(
                        label = S(R.string.studio_usage_reset),
                        icon = StudioIcons.ResetUsage,
                        tint = Ios.Orange,
                        enabled = !busy,
                    ) { confirmResetUsage = true }
                }
            } else {
                InfoCard(S(R.string.studio_renew_engine_old))
            }
        }

        // ---- the configs behind that link ----------------------------------------------
        //
        // Plural, because a config is the unit that can be revoked on its own: someone with a phone
        // and a laptop who loses the phone should lose one link rather than their subscription.
        // «لوکیشن‌ها»: what kind of user this is, from which countries, on which ports — the same
        // facts the list's badge and the person's own status page show, and the way to change them.
        SettingsSectionHeader(S(R.string.studio_locations))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.studio_user_kind),
                value = if (u.locations.isEmpty()) S(R.string.studio_user_kind_simple)
                else S(R.string.studio_user_kind_multi),
                icon = StudioIcons.Locations,
                tint = Ios.Indigo,
                showChevron = false,
            )
            if (u.locations.isNotEmpty()) {
                Separator()
                SettingsRow(
                    title = S(R.string.studio_user_countries),
                    subtitle = u.locations.joinToString("، ") { ExitCountries.label(it) },
                    icon = StudioIcons.OwnServer,
                    tint = Ios.Teal,
                    showChevron = false,
                )
            }
            Separator()
            SettingsRow(
                title = S(R.string.studio_user_ports),
                value = u.ports.ifEmpty { listOf(443) }.joinToString("، "),
                icon = StudioIcons.Endpoints,
                tint = Ios.Gray,
                showChevron = false,
            )
            Separator()
            SettingsActionRow(
                label = S(R.string.studio_user_edit_locations),
                icon = StudioIcons.Edit,
                tint = Ios.Blue,
                onClick = { onEditLocations(u.username) },
            )
        }

        SettingsSectionHeader(S(R.string.studio_section_configs))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.studio_section_configs),
                icon = StudioIcons.Config,
                tint = Ios.Indigo,
                onClick = { onOpenConfigs(u.username) },
            )
            Separator()
            // Right beside the configs, because that is what it changes -- and one row up from
            // "delete", because it is what to try before concluding somebody is unreachable.
            SettingsRow(
                title = S(R.string.studio_combine),
                subtitle = S(R.string.studio_combine_row_sub),
                icon = StudioIcons.Combine,
                tint = Ios.Teal,
                onClick = { onCombine(u.username) },
            )
        }

        // ---- on / off, delete ---------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_section_actions))
        SettingsGroup {
            val on = u.stoppedReason() != StoppedReason.DISABLED
            SettingsActionRow(
                label = if (on) S(R.string.studio_disable) else S(R.string.studio_enable),
                icon = StudioIcons.Power,
                tint = if (on) Ios.Orange else Ios.Green,
                enabled = !busy,
            ) {
                busy = true; error = null
                scope.launch {
                    when (val res = store.setEnabled(account, userId, !on)) {
                        is StudioResult.Ok -> user = res.value
                        is StudioResult.Err -> error = messageForDetail(context, res.error.code)
                    }
                    busy = false
                }
            }
            // To the account's central Worker (data/studio/central): only for someone whose term has
            // not ended — a move never gives time away — and only when a central Worker has room.
            if (u.stoppedReason() == null && store.centralTargetFor(account) != null) {
                Separator()
                SettingsActionRow(
                    label = com.mlmvpn.scanner.store.tr("انتقال به ورکر مرکزی", "Move to the central Worker"),
                    icon = StudioIcons.Combine,
                    tint = Ios.Indigo,
                    enabled = !busy,
                ) { confirmMove = true }
            }
        }

        // ---- what they actually did ----------------------------------------------------
        //
        // Two different questions, deliberately side by side. The history says WHEN they connected
        // and how much moved; the feed says why a connection was refused. An operator answering
        // "it stopped working" needs both, and needs them in that order: a person with no recent
        // session and no refusal has a network problem, one with refusals has an account problem,
        // and one with sessions that carried nothing has a config problem.
        if (canSessions) {
            SettingsSectionHeader(S(R.string.studio_section_history))
            when {
                loadingHistory -> WizardBusy(S(R.string.studio_loading))

                history.isEmpty() -> InfoCard(S(R.string.studio_history_none), underHeader = true)

                else -> {
                    SettingsGroup {
                        history.forEachIndexed { index, session ->
                            if (index > 0) Separator()
                            SettingsRow(
                                title = whenText(session.endedAt),
                                value = if (session.carriedNothing) null else bytesFa(session.bytes),
                                // The row that carried nothing is the useful one, so it is named
                                // rather than shown as a zero: "connected and nothing went
                                // through" is a different problem from "connected and used 2 MB",
                                // and a `۰` reads as the second.
                                titleColor = if (session.carriedNothing) Ios.Orange else Ios.Label,
                                subtitle = listOfNotNull(
                                    session.configLabel,
                                    sessionLength(session.durationMs),
                                    if (session.carriedNothing) S(R.string.studio_history_empty_row) else null,
                                ).joinToString(" · ").ifBlank { null },
                                showChevron = false,
                            )
                        }
                    }
                    InfoCard(S(R.string.studio_history_note))
                }
            }

            if (userActivity.isNotEmpty()) {
                SettingsSectionHeader(S(R.string.studio_section_user_activity))
                SettingsGroup {
                    userActivity.forEachIndexed { index, entry ->
                        if (index > 0) Separator()
                        SettingsRow(
                            title = activityLabel(entry.kind),
                            value = whenText(entry.ts),
                            titleColor = if (entry.severity == ActivitySeverity.ERROR) Ios.Red else Ios.Orange,
                            showChevron = false,
                        )
                    }
                }
                InfoCard(S(R.string.studio_user_activity_note))
            }
        }

        SettingsSectionHeader(S(R.string.studio_section_danger))
        SettingsGroup {
            SettingsActionRow(
                label = S(R.string.studio_delete),
                icon = StudioIcons.Delete,
                tint = Ios.Red,
                enabled = !busy,
            ) { confirmDelete = true }
        }

        Spacer(Modifier.height(28.dp))
    }

    if (confirmResetUsage) {
        IosAlert(
            title = S(R.string.studio_usage_reset_title),
            message = S(R.string.studio_usage_reset_body),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { confirmResetUsage = false }),
                IosAlertAction(S(R.string.studio_usage_reset), {
                    confirmResetUsage = false
                    val acc = account
                    if (acc != null) {
                        busy = true; error = null
                        scope.launch {
                            val res = store.renew(acc, userId, mode = RenewMode.RESET_USAGE)
                            when (res) {
                                is StudioResult.Ok -> {
                                    user = res.value
                                    note = context.getString(R.string.studio_saved)
                                }
                                is StudioResult.Err -> error = messageForDetail(context, res.error.code)
                            }
                            busy = false
                        }
                    }
                }, destructive = true),
            ),
            onDismiss = { confirmResetUsage = false },
        )
    }

    if (confirmRotate) {
        val acc = account
        IosAlert(
            title = S(R.string.studio_rotate_title),
            message = S(R.string.studio_rotate_body),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { confirmRotate = false }),
                IosAlertAction(S(R.string.studio_rotate_confirm), {
                    confirmRotate = false
                    if (acc != null) {
                        busy = true; error = null
                        scope.launch {
                            when (val res = StudioHttpApi(context, acc).rotateSubscription(userId)) {
                                is StudioResult.Ok -> { sub = res.value; note = S_rotated(context) }
                                is StudioResult.Err -> error = messageForDetail(context, res.error.code)
                            }
                            busy = false
                        }
                    }
                }, destructive = true),
            ),
            onDismiss = { confirmRotate = false },
        )
    }

    // Block and forget are different actions and both are offered, because they answer different
    // questions: a device that is simply no longer used should give its slot back, while one that
    // should never have had access has to stay refused even though it will keep trying.
    deviceMenu?.let { device ->
        val acc = account
        fun act(action: String) {
            deviceMenu = null
            if (acc == null) return
            busy = true; error = null
            scope.launch {
                when (val res = StudioHttpApi(context, acc).deviceAction(userId, device.hash, action)) {
                    is StudioResult.Ok -> { note = context.getString(R.string.studio_saved); reload() }
                    is StudioResult.Err -> error = messageForDetail(context, res.error.code)
                }
                busy = false
            }
        }
        IosAlert(
            title = device.clientHint?.take(40) ?: S(R.string.studio_device_unknown),
            message = S(R.string.studio_device_actions_body),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { deviceMenu = null }),
                if (device.blocked) IosAlertAction(S(R.string.studio_device_unblock), { act("unblock") })
                else IosAlertAction(S(R.string.studio_device_block), { act("block") }, destructive = true),
                IosAlertAction(S(R.string.studio_device_forget), { act("forget") }, destructive = true),
            ),
            onDismiss = { deviceMenu = null },
        )
    }

    if (confirmMove) {
        val acc = account
        val target = acc?.let { store.centralTargetFor(it) }
        IosAlert(
            title = com.mlmvpn.scanner.store.tr("انتقال به ورکر مرکزی؟", "Move to the central Worker?"),
            message = com.mlmvpn.scanner.store.tr(
                "همین کاربر با همین نام و تاریخ پایان روی ورکر مرکزی ساخته می‌شود و کانفیگ تازه می‌گیرد. سقف حجم و دستگاه منتقل نمی‌شود. لینک فعلی‌اش تا پایان زمانش کار می‌کند؛ اگر نمی‌خواهید، بعد از فرستادن کانفیگ تازه آن را اینجا خاموش کنید.",
                "The same person, with the same name and end date, is made on the central Worker with new configs. Volume and device limits do not move. Their current link keeps working until its end; switch it off here after sending the new configs if you do not want that.",
            ),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { confirmMove = false }),
                IosAlertAction(com.mlmvpn.scanner.store.tr("انتقال", "Move"), {
                    confirmMove = false
                    val from = acc ?: return@IosAlertAction
                    val to = target ?: return@IosAlertAction
                    val who = user ?: return@IosAlertAction
                    busy = true; error = null
                    scope.launch {
                        when (val res = store.moveToCentral(from, who, to)) {
                            is StudioResult.Ok -> onMoved(to.id, res.value.id)
                            is StudioResult.Err -> error = com.mlmvpn.scanner.ui.configstudio.parts.CentralKit.errorText(context, res.error)
                        }
                        busy = false
                    }
                }),
            ),
            onDismiss = { confirmMove = false },
        )
    }

    if (confirmDelete) {
        val acc = account
        IosAlert(
            title = S(R.string.studio_delete_title),
            message = S(R.string.studio_delete_body),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { confirmDelete = false }),
                IosAlertAction(S(R.string.studio_delete), {
                    confirmDelete = false
                    if (acc != null) {
                        busy = true; error = null
                        scope.launch {
                            when (val res = store.deleteUser(acc, userId)) {
                                is StudioResult.Ok -> onBack()
                                is StudioResult.Err -> {
                                    error = messageForDetail(context, res.error.code); busy = false
                                }
                            }
                        }
                    }
                }, destructive = true),
            ),
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * One device: what it looked like, and whether it is connected now or when it was last seen.
 * Tapping it offers block / forget.
 */
@Composable
private fun DeviceRow(device: StudioDevice, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            device.clientHint?.take(40) ?: S(R.string.studio_device_unknown),
            color = if (device.blocked) Ios.SecondaryLabel else Ios.Label,
            fontSize = StudioType.Body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        when {
            device.blocked -> StatusLine(S(R.string.studio_device_blocked), Ios.Red)
            device.online -> StatusLine(S(R.string.studio_device_online), Ios.Green)
            else -> StatusLine(deviceSubtitle(device), Ios.Gray)
        }
    }
}

/**
 * When this device was last seen, and what it looked like.
 *
 * Last-seen rather than first-seen, because the question an operator has in front of a device list
 * is "is this one still in use", which is the one that decides whether to forget it.
 */
@Composable
private fun deviceSubtitle(device: StudioDevice): String {
    val ageMs = System.currentTimeMillis() - device.lastSeen
    val minutes = (ageMs / 60000).coerceAtLeast(0)
    return when {
        device.lastSeen <= 0 -> S(R.string.studio_device_never)
        minutes < 1 -> S(R.string.studio_device_now)
        minutes < 60 -> S(R.string.studio_sync_minutes).replace("%1\$s", faNum(minutes))
        else -> S(R.string.studio_sync_hours).replace("%1\$s", faNum(minutes / 60))
    }
}

/**
 * How long a connection lasted, in the largest unit that is still honest.
 *
 * Seconds below a minute rather than "۰ دقیقه": a session that lasted eleven seconds is the
 * signature of a client that connected, failed to carry anything and gave up — which is exactly
 * what the operator is trying to see.
 */
@Composable
private fun sessionLength(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> S(R.string.studio_history_seconds).replace("%1", faNum(seconds))
        seconds < 3600 -> S(R.string.studio_history_minutes).replace("%1", faNum(seconds / 60))
        else -> S(R.string.studio_history_hours).replace("%1", faNum(seconds / 3600))
    }
}

/** Mapped from the engine's stable code, never its message. */
private fun messageForDetail(context: android.content.Context, code: String): String = context.getString(
    when (code) {
        "network" -> R.string.studio_err_network
        "unauthorized" -> R.string.studio_err_unauthorized
        "username_taken" -> R.string.studio_err_username_taken
        "schema_stale" -> R.string.studio_err_schema_stale
        else -> R.string.studio_err_generic
    }
)

/**
 * A typed tag line, split into tags.
 *
 * Both separators, because the field says «برچسب‌ها را با ویرگول جدا کنید» in a
 * right-to-left field, and the comma an Persian keyboard produces is «،» rather than ",". Accepting
 * only the ASCII one would turn «vip، tehran» into a single tag named "vip، tehran" — which then
 * appears in the filter row as a chip nobody can match.
 *
 * The result is stored comma-joined, so a tag may not itself contain a comma. Trimmed, de-duplicated
 * and order-preserving: two identical tags are one tag, and the order is the operator's.
 */
private fun splitTags(raw: String): List<String> =
    raw.split(',', '،', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

private fun S_copied(c: android.content.Context) = c.getString(R.string.studio_copied)
private fun S_renewed(c: android.content.Context) = c.getString(R.string.studio_renewed)
private fun S_rotated(c: android.content.Context) = c.getString(R.string.studio_rotated)
private fun S_needsInput(c: android.content.Context) = c.getString(R.string.studio_renew_needs_input)
private fun S_shareSubject(c: android.content.Context) = c.getString(R.string.studio_share_subject)
private fun S_exported(c: android.content.Context) = c.getString(R.string.studio_exported)
private fun S_exportFailed(c: android.content.Context) = c.getString(R.string.studio_export_failed)
