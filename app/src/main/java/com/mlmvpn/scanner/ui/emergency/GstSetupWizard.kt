package com.mlmvpn.scanner.ui.emergency

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.emergency.EmergencyColors
import com.mlmvpn.scanner.engines.gst.GstConfigManager
import com.mlmvpn.scanner.engines.gst.GstDiagnostics
import com.mlmvpn.scanner.engines.gst.GstRelay
import kotlinx.coroutines.launch

/**
 * Full-screen, step-by-step wizard that walks the user through manually deploying a
 * Google Apps Script relay and registering its Deployment ID. Replaces the old
 * automatic (API-based) deployment path.
 *
 * Steps:
 *   1. Choose / generate the security password (auth key).
 *   2. Confirm the password (baked into every script — shared across relays).
 *   3. Show the ready-to-paste script + full deployment guide + direct script.google.com link.
 *   4. Enter the Deployment ID and test the relay.
 *   5. Done — return to the connection screen.
 *
 * @param sharedAuthKey the password already in use for other relays (prefilled so all
 *   scripts share one key, per the tunnel's single-auth_key model). Empty on first setup.
 * @param editRelay when re-opening for an existing relay, its current values; null for a new relay.
 * @param onComplete called with the finished relay to persist; caller updates its list.
 * @param onClose dismiss without finishing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GstSetupWizard(
    sharedAuthKey: String,
    editRelay: GstRelay?,
    onComplete: (GstRelay) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    fun genKey() = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16)

    var step by remember { mutableStateOf(1) }
    var authKey by remember {
        mutableStateOf(
            editRelay?.authKey?.takeIf { it.isNotEmpty() }
                ?: sharedAuthKey.takeIf { it.isNotEmpty() }
                ?: genKey()
        )
    }
    var deploymentId by remember { mutableStateOf(editRelay?.deploymentId ?: "") }

    // Physical back steps the wizard backwards; from step 1 it closes the wizard.
    androidx.activity.compose.BackHandler(enabled = true) { if (step > 1) step-- else onClose() }

    // The finished script with the current auth key baked in. It is CLEANED (comments +
    // blank lines removed, folded to pure ASCII) so the copied/saved text is small and
    // byte-clean: no giant Unicode comment blocks that some device clipboards truncate
    // (which is what left a stray "*/" at the end of pasted code), and nothing that can
    // look "changed" when pasted into the Apps Script editor.
    val scriptCode = remember(authKey) {
        try {
            val raw = context.assets.open("gst/Code.gs").bufferedReader().use { it.readText() }
            cleanAppsScript(raw, authKey)
        } catch (e: Exception) {
            "// خطا در بارگذاری اسکریپت"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EmergencyColors.GoogleBg)
            .padding(
                top = com.mlmvpn.scanner.ui.LocalSystemTopPadding.current,
                bottom = com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current
            )
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (step > 1) step-- else onClose() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "بازگشت", tint = EmergencyColors.GoogleMuted)
            }
            Text(
                "راه‌اندازی رله گوگل",
                color = EmergencyColors.GoogleText,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) {
                Text("انصراف", color = EmergencyColors.GoogleMuted)
            }
        }

        StepProgressBar(current = step, total = 5)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            when (step) {
                1 -> StepPassword(authKey, onChange = { authKey = it }, onGenerate = { authKey = genKey() })
                2 -> StepConfirmPassword(authKey, onChange = { authKey = it }, onRegenerate = { authKey = genKey() })
                3 -> StepScript(
                    scriptCode = scriptCode,
                    onCopyCode = {
                        clipboard.setText(AnnotatedString(scriptCode))
                        toast(context, "کد کامل کپی شد (${scriptCode.length} کاراکتر)")
                    },
                    onSaveFile = {
                        val ok = saveScriptToDownloads(context, scriptCode)
                        toast(context, if (ok) "فایل در پوشه‌ی Downloads ذخیره شد: MLMVPN_relay.gs" else "ذخیره‌ی فایل ناموفق بود")
                    },
                    onOpenScriptSite = { openUrl(context, "https://script.google.com/home/projects/create") },
                    onCopyLink = {
                        clipboard.setText(AnnotatedString("https://script.google.com"))
                        toast(context, "لینک کپی شد")
                    }
                )
                4 -> StepDeploymentId(
                    deploymentId = deploymentId,
                    authKey = authKey,
                    onChange = { deploymentId = it }
                )
                5 -> StepDone()
            }
        }

        // Footer navigation
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (step > 1) {
                OutlinedButton(
                    onClick = { step-- },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("قبلی", color = EmergencyColors.GoogleText) }
            }
            Button(
                onClick = {
                    when (step) {
                        1 -> {
                            if (authKey.isBlank()) { toast(context, "لطفاً یک رمز وارد یا تولید کنید"); return@Button }
                            step = 2
                        }
                        2 -> step = 3
                        3 -> step = 4
                        4 -> {
                            if (deploymentId.isBlank()) { toast(context, "لطفاً Deployment ID را وارد کنید"); return@Button }
                            step = 5
                        }
                        5 -> onComplete(
                            (editRelay ?: GstRelay(deploymentId = "", authKey = "")).copy(
                                deploymentId = deploymentId.trim(),
                                authKey = authKey.trim()
                            )
                        )
                    }
                },
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = EmergencyColors.GoogleBlue)
            ) {
                Text(
                    if (step == 5) "ورود به صفحه اتصال" else "بعدی",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun StepProgressBar(current: Int, total: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 1..total) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (i <= current) EmergencyColors.GoogleBlue else EmergencyColors.GoogleSurface2
                    )
            )
        }
    }
    Text(
        "مرحله $current از $total",
        color = EmergencyColors.GoogleMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun StepTitle(title: String, subtitle: String) {
    Text(title, color = EmergencyColors.GoogleText, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    Spacer(Modifier.height(8.dp))
    Text(subtitle, color = EmergencyColors.GoogleMuted, fontSize = 14.sp, lineHeight = 22.sp)
    Spacer(Modifier.height(20.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepPassword(authKey: String, onChange: (String) -> Unit, onGenerate: () -> Unit) {
    StepTitle(
        "۱) رمز امنیتی رله",
        "این رمز، کلید امنیتی رله شماست. فقط دستگاه‌هایی که این رمز را دارند می‌توانند از اسکریپت شما " +
            "استفاده کنند. می‌توانید یک رمز دلخواه بگذارید یا یک رمز امن تولید کنید."
    )
    OutlinedTextField(
        value = authKey,
        onValueChange = onChange,
        label = { Text("رمز (Auth Key)", color = EmergencyColors.GoogleMuted) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = wizardFieldColors()
    )
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onGenerate,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Default.Refresh, contentDescription = null, tint = EmergencyColors.GoogleBlue)
        Spacer(Modifier.width(8.dp))
        Text("تولید رمز امن", color = EmergencyColors.GoogleBlue)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepConfirmPassword(authKey: String, onChange: (String) -> Unit, onRegenerate: () -> Unit) {
    StepTitle(
        "۲) تأیید رمز",
        "همین رمز به‌صورت خودکار داخل کدِ همه‌ی اسکریپت‌های شما قرار می‌گیرد؛ پس اگر چند حساب گوگل " +
            "استفاده می‌کنید، همه‌ی رله‌ها با همین یک رمز کار می‌کنند. اگر بخواهید می‌توانید همین‌جا تغییرش دهید."
    )
    OutlinedTextField(
        value = authKey,
        onValueChange = onChange,
        label = { Text("رمز نهایی", color = EmergencyColors.GoogleMuted) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = wizardFieldColors()
    )
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onRegenerate,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Default.Refresh, contentDescription = null, tint = EmergencyColors.GoogleMuted)
        Spacer(Modifier.width(8.dp))
        Text("تولید رمز جدید", color = EmergencyColors.GoogleMuted)
    }
    Spacer(Modifier.height(16.dp))
    InfoBox("💡 توصیه: این رمز را جایی یادداشت کنید. با همین رمز می‌توانید چند اسکریپت از حساب‌های مختلف بسازید.")
}

@Composable
private fun StepScript(
    scriptCode: String,
    onCopyCode: () -> Unit,
    onSaveFile: () -> Unit,
    onOpenScriptSite: () -> Unit,
    onCopyLink: () -> Unit
) {
    StepTitle(
        "۳) کد آماده و راهنمای استقرار",
        "کد زیر آماده است (رمز شما از قبل داخلش قرار گرفته). آن را کپی کنید و طبق مراحل روی " +
            "script.google.com مستقر کنید."
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onOpenScriptSite,
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = EmergencyColors.GoogleBlue)
        ) {
            Icon(Icons.Default.OpenInNew, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(6.dp))
            Text("باز کردن script.google.com", color = Color.White, fontSize = 13.sp)
        }
        OutlinedButton(
            onClick = onCopyLink,
            modifier = Modifier.height(48.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = "کپی لینک", tint = EmergencyColors.GoogleMuted)
        }
    }

    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("کد اسکریپت", color = EmergencyColors.GoogleText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text("(${scriptCode.length} کاراکتر)", color = EmergencyColors.GoogleMuted, fontSize = 12.sp)
    }
    Spacer(Modifier.height(6.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Text(scriptCode, color = EmergencyColors.GoogleGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = onCopyCode,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = EmergencyColors.GoogleGreen)
    ) {
        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color.Black)
        Spacer(Modifier.width(8.dp))
        Text("کپی کردن کد", color = Color.Black, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onSaveFile,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Default.Download, contentDescription = null, tint = EmergencyColors.GoogleBlue)
        Spacer(Modifier.width(8.dp))
        Text("ذخیره به‌صورت فایل (اگر کپی ناقص بود)", color = EmergencyColors.GoogleBlue, fontSize = 13.sp)
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "کد کاملاً تمیز و بدون کاراکتر اضافه است و با } بسته می‌شود. اگر کلیپ‌بورد گوشی کد را ناقص " +
            "کپی کرد، از «ذخیره به‌صورت فایل» استفاده کنید؛ فایل عیناً و کامل ذخیره می‌شود.",
        color = EmergencyColors.GoogleMuted, fontSize = 12.sp, lineHeight = 18.sp
    )

    Spacer(Modifier.height(20.dp))
    InfoBox(
        "راهنمای گام‌به‌گام (روی گوشی):\n\n" +
            "۱. دکمه‌ی «کپی کردن کد» را بزنید.\n\n" +
            "۲. «باز کردن script.google.com» را بزنید. اگر خواست، مرورگر را روی حالت دسکتاپ " +
            "(Desktop site) بگذارید تا ویرایشگر کامل باز شود، و یک پروژه‌ی جدید (New project) بسازید.\n\n" +
            "۳. همه‌ی کد پیش‌فرض داخل ویرایشگر را پاک کنید و کد کپی‌شده را Paste کنید. سپس با زدن " +
            "آیکون ذخیره (💾) یا از منوی File → Save ذخیره کنید (روی گوشی کلید Ctrl نداریم).\n\n" +
            "۴. روی «Deploy» → «New deployment» بزنید و این تنظیمات را دقیقاً اعمال کنید:\n" +
            "    • Select type: Web app\n" +
            "    • Execute as: Me\n" +
            "    • Who has access: Anyone\n\n" +
            "۵. «Deploy» را بزنید و Authorize کنید:\n" +
            "    Review Permissions → Advanced → Go to … (unsafe) → Allow\n\n" +
            "۶. «Deployment ID» را کپی کنید (رشته‌ای که با AKfy… شروع می‌شود) و به مرحله‌ی بعد بروید.\n\n" +
            "💡 اگر Paste کردن کد در گوشی سخت بود، به‌جای کپی از «ذخیره به‌صورت فایل» استفاده کنید و همان " +
            "فایل را در ویرایشگر باز/وارد کنید.\n\n" +
            "⚠️ اگر بعداً خطای ۴۰۱ یا ۴۰۳ گرفتید، یعنی مرحله‌ی ۴ (Anyone/Me) یا مرحله‌ی ۵ (Authorize) درست انجام نشده."
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepDeploymentId(deploymentId: String, authKey: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<GstDiagnostics.Report?>(null) }

    StepTitle(
        "۴) شناسه استقرار (Deployment ID)",
        "شناسه‌ای که در مرحله‌ی قبل از Google کپی کردید را اینجا وارد کنید، سپس «تست رله» را بزنید " +
            "تا مطمئن شوید درست کار می‌کند."
    )
    OutlinedTextField(
        value = deploymentId,
        onValueChange = onChange,
        label = { Text("Deployment ID (مثلاً AKfy…)", color = EmergencyColors.GoogleMuted) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = wizardFieldColors()
    )
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = {
            if (deploymentId.isBlank()) { toast(context, "ابتدا Deployment ID را وارد کنید"); return@Button }
            testing = true
            report = null
            scope.launch {
                val rep = GstDiagnostics.testDeployment(GstDiagnostics.execUrl(deploymentId), authKey)
                report = rep
                testing = false
            }
        },
        enabled = !testing,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = EmergencyColors.GoogleSurface2)
    ) {
        if (testing) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = EmergencyColors.GoogleBlue, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(if (testing) "در حال تست…" else "تست رله", color = EmergencyColors.GoogleText)
    }

    report?.let { r ->
        Spacer(Modifier.height(16.dp))
        val ok = r.result == GstDiagnostics.Result.OK
        val needsAuth = r.result == GstDiagnostics.Result.REDIRECT_BLOCKED
        val color = when {
            ok -> EmergencyColors.GoogleGreen
            needsAuth -> EmergencyColors.GoogleBlue
            else -> EmergencyColors.GoogleRed
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    when {
                        ok -> "✅ رله سالم است"
                        needsAuth -> "⚠️ رله ساخته شده اما هنوز تأیید (Authorize) نشده"
                        else -> "❌ رله مشکل دارد"
                    },
                    color = color, fontWeight = FontWeight.Bold, fontSize = 15.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(r.message, color = EmergencyColors.GoogleMuted, fontSize = 13.sp)
                if (needsAuth) {
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { openUrl(context, GstDiagnostics.execUrl(deploymentId)) }) {
                        Text("باز کردن صفحه‌ی تأیید در مرورگر", color = EmergencyColors.GoogleBlue)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepDone() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(80.dp).clip(CircleShape).background(EmergencyColors.GoogleGreen.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = EmergencyColors.GoogleGreen, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("رله آماده است!", color = EmergencyColors.GoogleText, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "رله شما ذخیره شد. با زدن دکمه‌ی زیر به صفحه‌ی اتصال برمی‌گردید و می‌توانید متصل شوید. " +
                "برای دور زدن محدودیت روزانه، می‌توانید از حساب‌های گوگل دیگر هم رله اضافه کنید؛ سیستم " +
                "بار را بین همه‌ی رله‌ها پخش می‌کند و اگر یکی تمام شد خودکار سراغ بعدی می‌رود.",
            color = EmergencyColors.GoogleMuted, fontSize = 14.sp, lineHeight = 22.sp
        )
    }
}

@Composable
private fun InfoBox(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = EmergencyColors.GoogleSurface),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, EmergencyColors.GoogleSurface2)
    ) {
        Text(text, color = EmergencyColors.GoogleMuted, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(14.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun wizardFieldColors() = TextFieldDefaults.outlinedTextFieldColors(
    focusedBorderColor = EmergencyColors.GoogleBlue,
    unfocusedBorderColor = EmergencyColors.GoogleSurface2,
    focusedTextColor = EmergencyColors.GoogleText,
    unfocusedTextColor = EmergencyColors.GoogleText
)

private fun openUrl(context: android.content.Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    } catch (e: Exception) {
        toast(context, "لینک: $url")
    }
}

private fun toast(context: android.content.Context, msg: String) {
    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
}

// Turns the raw Apps Script asset into a small, byte-clean, pure-ASCII script for copy/paste:
//   - strips the UTF-8 BOM,
//   - drops whole-line "//" comments, full-line block comments, and blank lines (all block
//     openers in Code.gs are full-line, and JS has no multi-line string literals in this file,
//     so this can't touch executable code — verified),
//   - folds any stray non-ASCII (only ever inside the rare inline comment) to ASCII,
//   - bakes in the auth key and guarantees a single trailing newline so the final "}" survives
//     even paste targets that drop a missing terminator.
// Result: ~1/3 the size, no block comments at all (so no stray comment-close can appear at the
// end), and nothing that renders as "changed" when pasted.
fun cleanAppsScript(rawIn: String, authKey: String): String {
    val raw = rawIn.removePrefix("﻿")
    val sb = StringBuilder()
    var inBlock = false
    for (rawLine in raw.split("\n")) {
        val line = rawLine.trimEnd('\r')
        val t = line.trimStart()
        if (inBlock) {
            if (t.contains("*/")) inBlock = false
            continue
        }
        if (t.startsWith("/*")) {
            if (!t.contains("*/")) inBlock = true
            continue
        }
        if (t.startsWith("//")) continue
        if (t.isBlank()) continue
        val ascii = buildString {
            for (c in line) append(if (c.code in 9..126) c else ' ')
        }
        sb.append(ascii.trimEnd()).append('\n')
    }
    var out = sb.toString().trimEnd()
    if (authKey.isNotEmpty()) out = out.replace("CHANGE_ME_TO_A_STRONG_SECRET", authKey)
    return out + "\n"
}

/** Writes the (already cleaned) script to Downloads as a byte-exact file — a clipboard-free,
 * truncation-proof delivery path. Returns true on success. */
fun saveScriptToDownloads(context: android.content.Context, code: String): Boolean {
    return try {
        val bytes = code.toByteArray(Charsets.UTF_8)
        val name = "MLMVPN_relay.gs"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val sel = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?"
            resolver.delete(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, sel, arrayOf(name))
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
        } else {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val f = java.io.File(dir, name)
            f.writeBytes(bytes)
            android.media.MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf("text/plain"), null)
        }
        true
    } catch (e: Exception) {
        false
    }
}
