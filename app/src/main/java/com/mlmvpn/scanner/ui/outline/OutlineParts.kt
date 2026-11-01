package com.mlmvpn.scanner.ui.outline

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.engines.outline.OutlineBrain
import com.mlmvpn.scanner.engines.outline.OutlineEngine
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.amnezia.fa
import com.mlmvpn.scanner.ui.settings.Ios
import java.util.Locale

/** Pieces the «Outline» pages share. */

internal fun countryName(cc: String?): String = when {
    cc == null || cc == OutlineBrain.UNKNOWN -> tr("نامشخص", "Unknown")
    else -> Locale("", cc).getDisplayCountry(com.mlmvpn.scanner.utils.AppLocaleManager.getResolvedLocale()).ifBlank { cc }
}

internal fun flagOf(cc: String?): String = when {
    cc == null || cc.length != 2 || !cc.all { it in 'A'..'Z' || it in 'a'..'z' } -> "🌐"
    else -> cc.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

internal fun msText(ms: Long): String = tr("${fa(ms.toInt())} میلی‌ثانیه", "$ms ms")

/** Bits per second as the speed tile reads it: "12.4 Mbps". */
internal fun speedText(bps: Long): String {
    val mbps = bps / 1_000_000.0
    val n = if (mbps >= 10) "%.0f".format(Locale.US, mbps) else "%.1f".format(Locale.US, mbps)
    return tr("${fa(n)} مگابیت", "$n Mbps")
}

/** "3 minutes ago", for a list's or a test's age. */
internal fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= 0) return tr("هرگز", "never")
    val m = ((now - at) / 60_000).coerceAtLeast(0)
    return when {
        m < 1 -> tr("همین الان", "just now")
        m < 60 -> tr("${fa(m.toInt())} دقیقه پیش", "$m min ago")
        m < 48 * 60 -> tr("${fa((m / 60).toInt())} ساعت پیش", "${m / 60} h ago")
        else -> tr("${fa((m / 1440).toInt())} روز پیش", "${m / 1440} d ago")
    }
}

/** A list's own check time: the clock ("۲۲:۳۰") within the last day, else how long ago. */
internal fun checkTime(at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= 0 || now - at > 20 * 3_600_000L) return ago(at, now)
    return fa(java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(at)))
}

/** The cipher, short: "ChaCha20", "AES-256", "2022 AES-128". */
internal fun cipherLabel(method: String): String = when {
    method.startsWith("2022-blake3-aes-128") -> "SS2022 AES-128"
    method.startsWith("2022-blake3-aes-256") -> "SS2022 AES-256"
    method.startsWith("2022-blake3-chacha") -> "SS2022 ChaCha"
    method.startsWith("aes-128") -> "AES-128"
    method.startsWith("aes-256") -> "AES-256"
    method.startsWith("xchacha") -> "XChaCha20"
    else -> "ChaCha20"
}

/** «N سرور حذف شد — بازگردانی»: shown for six seconds after every delete. */
@Composable
internal fun OutlineUndoBar(modifier: Modifier = Modifier) {
    val count by OutlineEngine.undoCount.collectAsState()
    androidx.compose.animation.AnimatedVisibility(
        visible = count > 0, modifier = modifier,
        enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)
                .clip(RoundedCornerShape(16.dp)).background(Ios.DialogSurface)
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr("${fa(count)} سرور حذف شد", if (count == 1) "1 server deleted" else "$count servers deleted"),
                color = Ios.Label, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(
                tr("بازگردانی", "Undo"), color = Ios.Blue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { OutlineEngine.undoDelete() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

/** A key as a QR code, black on white so every camera reads it. */
@Composable
internal fun KeyQr(value: String) {
    val bitmap = remember(value) {
        runCatching {
            val hints = java.util.EnumMap<com.google.zxing.EncodeHintType, Any>(com.google.zxing.EncodeHintType::class.java)
            hints[com.google.zxing.EncodeHintType.MARGIN] = 1
            val m = com.google.zxing.qrcode.QRCodeWriter().encode(value, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512, hints)
            val bmp = android.graphics.Bitmap.createBitmap(m.width, m.height, android.graphics.Bitmap.Config.RGB_565)
            for (x in 0 until m.width) for (y in 0 until m.height) {
                bmp.setPixel(x, y, if (m.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
            bmp.asImageBitmap()
        }.getOrNull()
    }
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null,
                modifier = Modifier.size(240.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(10.dp))
        }
    }
}
