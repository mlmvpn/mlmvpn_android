package com.mlmvpn.scanner.ui.geph

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import com.mlmvpn.core.geph.GephAccount
import com.mlmvpn.core.geph.GephCredential
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.ui.emergency.faDigits
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsTextRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «حساب گف»: everything the network lets a user do with an account, and nothing it does not.
 *
 * A page, not a dialog -- the app's rule for anything with content. Confirmations (replacing the
 * code, removing the account) are the one place an alert is used.
 */
@Composable
fun GephAccountScreen(onBack: () -> Unit, backLabel: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Read off the main thread: the credential is decrypted through the Keystore, which can take
    // seconds, under GephAccount's lock (Geph's freezes and ANRs, 1.2.41).
    var cred by remember { mutableStateOf<GephCredential?>(null) }
    var loading by remember { mutableStateOf(GephAccount.hasAccount(ctx)) }
    var info by remember { mutableStateOf(GephAccount.cachedInfo(ctx)) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun reload() {
        info = GephAccount.cachedInfo(ctx)
        scope.launch {
            cred = withContext(Dispatchers.IO) { GephAccount.credential(ctx) }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun refresh() {
        busy = "refresh"
        scope.launch {
            val r = withContext(Dispatchers.IO) { GephAccount.refreshInfo(ctx) }
            r.onSuccess { info = it; if (it == null) message = ctx.getString(R.string.geph_not_an_account) }
                .onFailure { message = ctx.getString(R.string.geph_error, it.message ?: "") }
            busy = null
        }
    }

    IosScreen(title = stringResource(R.string.geph_account_title), onBack = onBack, backLabel = backLabel) {
        Spacer(Modifier.height(12.dp))
        message?.let {
            SettingsFooter(it)
            Spacer(Modifier.height(8.dp))
        }
        if (cred == null && loading) {
            // The stored account is still being read; a moment, not "no account".
        } else if (cred == null) {
            NoAccount(
                busy = busy,
                setBusy = { busy = it },
                onMessage = { message = it },
                onDone = {
                    reload()
                    refresh()
                    applyGephNow(ctx)
                },
            )
        } else {
            HasAccount(
                cred = cred!!,
                info = info,
                busy = busy,
                setBusy = { busy = it },
                onMessage = { message = it },
                onRefresh = { refresh() },
                onChanged = {
                    reload()
                    applyGephNow(ctx)
                },
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun NoAccount(
    busy: String?,
    setBusy: (String?) -> Unit,
    onMessage: (String?) -> Unit,
    onDone: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf(-1.0) }
    var code by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var cancel by remember { mutableStateOf(false) }

    SettingsSectionHeader(stringResource(R.string.geph_new_account_section))
    SettingsGroup {
        if (progress >= 0) {
            Text(
                stringResource(R.string.geph_register_progress, faDigits((progress * 100).toInt().toString())),
                color = Ios.Label,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            LinearProgressIndicator(
                progress = progress.toFloat(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Separator()
            SettingsActionRow(
                label = stringResource(R.string.geph_cancel),
                icon = Icons.Default.Delete,
                tint = Ios.Red,
                onClick = { cancel = true },
            )
        } else {
            SettingsActionRow(
                label = stringResource(R.string.geph_make_free_account),
                icon = Icons.Default.PersonAdd,
                tint = Ios.Blue,
                enabled = busy == null,
                onClick = {
                    setBusy("register")
                    onMessage(null)
                    cancel = false
                    progress = 0.0
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            GephAccount.register(ctx, onProgress = { progress = it }, cancelled = { cancel })
                        }
                        progress = -1.0
                        setBusy(null)
                        r.onSuccess {
                            // Said once, loudly, and copied: this code is the account. There is
                            // no e-mail to recover it with.
                            copyToClipboard(ctx, "Geph", it)
                            onDone()
                            onMessage(ctx.getString(R.string.geph_new_account_done, GephAccount.formatSecret(it)))
                        }.onFailure {
                            if (!cancel) onMessage(ctx.getString(R.string.geph_error, it.message ?: ""))
                        }
                    }
                },
            )
        }
    }
    SettingsFooter(stringResource(R.string.geph_new_account_footer))

    SettingsSectionHeader(stringResource(R.string.geph_login_section))
    SettingsGroup {
        SettingsTextRow(
            title = stringResource(R.string.geph_code_field),
            value = code,
            onValueChange = { code = it },
            icon = Icons.Default.Key,
            tint = Ios.Indigo,
            placeholder = "0000 0000 0000 0000 0000 0000",
            numeric = true,
        )
        Separator()
        SettingsActionRow(
            label = stringResource(R.string.geph_login),
            icon = Icons.Default.Login,
            tint = Ios.Blue,
            busy = busy == "login",
            enabled = busy == null && code.isNotBlank(),
            onClick = {
                val secret = GephAccount.normalizeSecret(code)
                if (secret == null) {
                    onMessage(ctx.getString(R.string.geph_code_malformed))
                    return@SettingsActionRow
                }
                setBusy("login")
                onMessage(null)
                scope.launch {
                    val r = withContext(Dispatchers.IO) { GephAccount.secretStatus(ctx, secret) }
                    setBusy(null)
                    r.onSuccess { status ->
                        when (status) {
                            is GephAccount.SecretStatus.Current -> {
                                GephAccount.setSecret(ctx, secret)
                                onDone()
                            }
                            GephAccount.SecretStatus.Retired -> onMessage(ctx.getString(R.string.geph_code_retired))
                            GephAccount.SecretStatus.Invalid -> onMessage(ctx.getString(R.string.geph_code_invalid))
                        }
                    }.onFailure { onMessage(ctx.getString(R.string.geph_error, it.message ?: "")) }
                }
            },
        )
    }
    SettingsFooter(stringResource(R.string.geph_login_footer))

    SettingsSectionHeader(stringResource(R.string.geph_legacy_section))
    SettingsGroup {
        SettingsTextRow(
            title = stringResource(R.string.geph_legacy_user),
            value = user,
            onValueChange = { user = it },
            icon = Icons.Default.Person,
            tint = Ios.Gray,
        )
        Separator()
        SettingsTextRow(
            title = stringResource(R.string.geph_legacy_pass),
            value = pass,
            onValueChange = { pass = it },
            icon = Icons.Default.Key,
            tint = Ios.Gray,
        )
        Separator()
        SettingsActionRow(
            label = stringResource(R.string.geph_login),
            icon = Icons.Default.Login,
            tint = Ios.Blue,
            busy = busy == "legacy",
            enabled = busy == null && user.isNotBlank() && pass.isNotBlank(),
            onClick = {
                setBusy("legacy")
                onMessage(null)
                GephAccount.setLegacy(ctx, user.trim(), pass)
                scope.launch {
                    val r = withContext(Dispatchers.IO) { GephAccount.refreshInfo(ctx) }
                    setBusy(null)
                    r.onSuccess { info ->
                        if (info == null) {
                            GephAccount.forget(ctx)
                            onMessage(ctx.getString(R.string.geph_legacy_wrong))
                        } else {
                            onDone()
                        }
                    }.onFailure {
                        GephAccount.forget(ctx)
                        onMessage(ctx.getString(R.string.geph_error, it.message ?: ""))
                    }
                }
            },
        )
    }
    SettingsFooter(stringResource(R.string.geph_legacy_footer))
}

@Composable
private fun HasAccount(
    cred: GephCredential,
    info: GephAccount.Info?,
    busy: String?,
    setBusy: (String?) -> Unit,
    onMessage: (String?) -> Unit,
    onRefresh: () -> Unit,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reveal by remember { mutableStateOf(false) }
    var confirmRotate by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var voucher by remember { mutableStateOf<GephAccount.Voucher?>(null) }
    var giftCode by remember { mutableStateOf("") }

    SettingsSectionHeader(stringResource(R.string.geph_account_section))
    SettingsGroup {
        SettingsRow(
            title = stringResource(R.string.geph_level),
            icon = Icons.Default.AccountCircle,
            tint = Ios.Blue,
            value = levelLabel(info),
            showChevron = false,
        )
        info?.let { i ->
            Separator()
            SettingsRow(
                title = stringResource(R.string.geph_user_id),
                value = faDigits(i.userId.toString()),
                showChevron = false,
            )
            if (i.level != GephAccount.Level.FREE && i.plusExpiresUnix != null) {
                Separator()
                SettingsRow(
                    title = stringResource(R.string.geph_expires),
                    icon = Icons.Default.Schedule,
                    tint = Ios.Orange,
                    value = gephDate(i.plusExpiresUnix),
                    subtitle = if (i.recurring) stringResource(R.string.geph_recurring) else null,
                    showChevron = false,
                )
            }
            if (i.bwLimitMb != null) {
                Separator()
                SettingsRow(
                    title = stringResource(R.string.geph_data_used),
                    icon = Icons.Default.DataUsage,
                    tint = Ios.Teal,
                    value = stringResource(
                        R.string.geph_mb_of,
                        faDigits((i.bwUsedMb ?: 0).toString()),
                        faDigits(i.bwLimitMb.toString()),
                    ),
                    subtitle = i.bwRenewUnix?.let { stringResource(R.string.geph_renews, gephDate(it)) },
                    showChevron = false,
                )
            }
        }
        Separator()
        SettingsActionRow(
            label = stringResource(R.string.geph_refresh),
            icon = Icons.Default.Refresh,
            tint = Ios.Blue,
            busy = busy == "refresh",
            enabled = busy == null,
            onClick = onRefresh,
        )
    }
    if (info?.level == GephAccount.Level.FREE) SettingsFooter(stringResource(R.string.geph_free_footer))

    when (cred) {
        is GephCredential.Secret -> {
            SettingsSectionHeader(stringResource(R.string.geph_code_section))
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.geph_code_field),
                    icon = Icons.Default.Key,
                    tint = Ios.Indigo,
                    value = if (reveal) GephAccount.formatSecret(cred.secret)
                        else "•••• •••• " + cred.secret.takeLast(4),
                    showChevron = false,
                )
                Separator()
                SettingsActionRow(
                    label = stringResource(if (reveal) R.string.geph_hide_code else R.string.geph_show_code),
                    icon = Icons.Default.Visibility,
                    tint = Ios.Gray,
                    onClick = { reveal = !reveal },
                )
                Separator()
                SettingsActionRow(
                    label = stringResource(R.string.geph_copy_code),
                    icon = Icons.Default.ContentCopy,
                    tint = Ios.Blue,
                    onClick = { copyToClipboard(ctx, "Geph", cred.secret) },
                )
                if (GephAccount.canRotate(ctx)) {
                    Separator()
                    SettingsActionRow(
                        label = stringResource(R.string.geph_rotate),
                        icon = Icons.Default.Autorenew,
                        tint = Ios.Orange,
                        busy = busy == "rotate",
                        enabled = busy == null,
                        onClick = { confirmRotate = true },
                    )
                }
            }
            SettingsFooter(
                stringResource(R.string.geph_code_footer) +
                    if (GephAccount.canRotate(ctx)) "\n\n" + stringResource(R.string.geph_rotate_footer) else "",
            )

            SettingsSectionHeader(stringResource(R.string.geph_voucher_section))
            SettingsGroup {
                SettingsActionRow(
                    label = stringResource(R.string.geph_free_voucher),
                    icon = Icons.Default.CardGiftcard,
                    tint = Ios.Pink,
                    busy = busy == "voucher",
                    enabled = busy == null,
                    onClick = {
                        setBusy("voucher")
                        onMessage(null)
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { GephAccount.freeVoucher(ctx) }
                            setBusy(null)
                            r.onSuccess { v ->
                                voucher = v
                                if (v == null) onMessage(ctx.getString(R.string.geph_no_free_voucher))
                                else giftCode = v.code
                            }.onFailure { onMessage(ctx.getString(R.string.geph_error, it.message ?: "")) }
                        }
                    },
                )
                voucher?.let { v ->
                    val lang = if (com.mlmvpn.scanner.utils.AppLocaleManager.isFarsi()) "fa" else "en"
                    val html = v.explanation[lang] ?: v.explanation["en"] ?: v.explanation.values.firstOrNull()
                    if (!html.isNullOrBlank()) {
                        Separator()
                        Text(
                            HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim(),
                            color = Ios.SecondaryLabel,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
                Separator()
                SettingsTextRow(
                    title = stringResource(R.string.geph_gift_code),
                    value = giftCode,
                    onValueChange = { giftCode = it },
                    icon = Icons.Default.Redeem,
                    tint = Ios.Pink,
                )
                Separator()
                SettingsActionRow(
                    label = stringResource(R.string.geph_redeem),
                    icon = Icons.Default.Redeem,
                    tint = Ios.Green,
                    busy = busy == "redeem",
                    enabled = busy == null && giftCode.isNotBlank(),
                    onClick = {
                        setBusy("redeem")
                        onMessage(null)
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { GephAccount.redeem(ctx, giftCode) }
                            setBusy(null)
                            r.onSuccess { days ->
                                onMessage(
                                    if (days > 0) ctx.getString(R.string.geph_redeemed, faDigits(days.toString()))
                                    else ctx.getString(R.string.geph_redeem_nothing),
                                )
                                giftCode = ""
                                voucher = null
                                onRefresh()
                                onChanged()
                            }.onFailure { onMessage(ctx.getString(R.string.geph_error, it.message ?: "")) }
                        }
                    },
                )
            }
            SettingsFooter(stringResource(R.string.geph_voucher_footer))
        }

        is GephCredential.Legacy -> {
            SettingsSectionHeader(stringResource(R.string.geph_legacy_section))
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.geph_legacy_user),
                    icon = Icons.Default.Person,
                    value = cred.username,
                    showChevron = false,
                )
            }
            SettingsFooter(stringResource(R.string.geph_legacy_has_footer))
        }
    }

    SettingsSectionHeader(stringResource(R.string.geph_remove_section))
    SettingsGroup {
        SettingsActionRow(
            label = stringResource(R.string.geph_remove),
            icon = Icons.Default.Delete,
            tint = Ios.Red,
            enabled = busy == null,
            onClick = { confirmForget = true },
        )
    }
    SettingsFooter(stringResource(R.string.geph_remove_footer))

    if (confirmRotate) {
        AlertDialog(
            onDismissRequest = { confirmRotate = false },
            title = { Text(stringResource(R.string.geph_rotate)) },
            text = { Text(stringResource(R.string.geph_rotate_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRotate = false
                    setBusy("rotate")
                    onMessage(null)
                    val before = info?.plusExpiresUnix ?: 0L
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            GephAccount.rotate(ctx).onSuccess { GephAccount.refreshInfo(ctx) }
                        }
                        setBusy(null)
                        r.onSuccess { fresh ->
                            val after = GephAccount.cachedInfo(ctx)?.plusExpiresUnix ?: 0L
                            val days = ((after - maxOf(before, System.currentTimeMillis() / 1000)) / 86_400L).toInt()
                            onMessage(
                                ctx.getString(R.string.geph_rotated, GephAccount.formatSecret(fresh)) +
                                    if (days > 0) "\n" + ctx.getString(R.string.geph_rotate_reward, faDigits(days.toString())) else "",
                            )
                            copyToClipboard(ctx, "Geph", fresh)
                            onChanged()
                        }.onFailure { onMessage(ctx.getString(R.string.geph_error, it.message ?: "")) }
                    }
                }) { Text(stringResource(R.string.geph_rotate_yes)) }
            },
            dismissButton = { TextButton(onClick = { confirmRotate = false }) { Text(stringResource(R.string.geph_cancel)) } },
        )
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.geph_remove)) },
            text = { Text(stringResource(R.string.geph_remove_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    GephAccount.forget(ctx)
                    onChanged()
                }) { Text(stringResource(R.string.geph_remove_yes), color = Ios.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.geph_cancel)) } },
        )
    }
}
