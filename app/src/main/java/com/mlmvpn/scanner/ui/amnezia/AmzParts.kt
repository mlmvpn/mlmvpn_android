package com.mlmvpn.scanner.ui.amnezia

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.engines.amnezia.AmzBrain
import com.mlmvpn.scanner.engines.amnezia.AmzKind
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsGroupScope
import java.util.Locale

/** Pieces both «آمنزیا» pages draw. */

/** A group's name: a country, «وارپ کلادفلر», or «نامشخص». */
@Composable
internal fun groupName(key: String?): String = when (key) {
    null, AmzBrain.UNKNOWN -> stringResource(R.string.amz_unknown_country)
    AmzBrain.WARP -> stringResource(R.string.amz_warp)
    else -> Locale("", key).getDisplayCountry(Locale.getDefault()).ifBlank { key }
}

/** A group's mark: the country's flag, a cloud for WARP, a globe for «نامشخص». */
internal fun groupFlag(key: String?): String = when {
    key == AmzBrain.WARP -> "☁️"
    key == null || key.length != 2 || !key.all { it in 'A'..'Z' || it in 'a'..'z' } -> "🌐"
    else -> key.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

/** Persian digits when the app speaks Persian. */
internal fun fa(n: Int): String = fa(n.toString())

internal fun fa(s: String): String =
    if (Locale.getDefault().language != "fa") s
    else s.map { c -> if (c in '0'..'9') '۰' + (c - '0') else c }.joinToString("")

internal fun kindLabel(k: AmzKind) = when (k) { AmzKind.HY2 -> "Hysteria2"; AmzKind.WG -> "WireGuard"; AmzKind.AWG -> "AmneziaWG" }

@Composable
internal fun kindColor(k: AmzKind) = when (k) { AmzKind.HY2 -> Ios.Purple; AmzKind.WG -> Ios.Teal; AmzKind.AWG -> Ios.Orange }

/** Delay colour bands: green under 300 ms, yellow under 800, orange above. */
@Composable
internal fun delayColor(ms: Long) = when { ms < 300 -> Ios.Green; ms < 800 -> Ios.Yellow; else -> Ios.Orange }

@Composable
internal fun Tag(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
internal fun DelayCapsule(ms: Long) {
    val c = delayColor(ms)
    Text(
        stringResource(R.string.amz_ms, fa(ms.toInt())), color = c, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.15f)).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** A round glass button for the navigation bar. */
@Composable
internal fun BarIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Ios.CardOverWallpaper)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = Ios.Label, modifier = Modifier.size(20.dp)) }
}

/** iOS's action sheet: a titled card of actions and a separate Cancel, over a dimmed screen. */
@Composable
internal fun ActionSheet(title: String, onDismiss: () -> Unit, content: @Composable SettingsGroupScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                title, color = Ios.SecondaryLabel, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ios.DialogSurface)) {
                SettingsGroup(horizontal = 0.dp, content = content)
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ios.DialogSurface)
                    .clickable(onClick = onDismiss).padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.amz_cancel), color = Ios.Blue, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** The bytes and display name of a picked file; null when it cannot be read or is too big. */
internal fun readUri(context: Context, uri: android.net.Uri): Pair<ByteArray, String>? = runCatching {
    val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment.orEmpty()
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf); if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > 8 * 1024 * 1024) return@runCatching null
        }
        out.toByteArray()
    } ?: return@runCatching null
    bytes to name
}.getOrNull()

/** «N سرور حذف شد — بازگردانی»: shown for a few seconds after every delete. */
@Composable
internal fun UndoBar(modifier: Modifier = Modifier) {
    val count by com.mlmvpn.scanner.engines.amnezia.AmzEngine.undoCount.collectAsState()
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
            Text(stringResource(R.string.amz_undo_deleted, fa(count)), color = Ios.Label, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.amz_undo), color = Ios.Blue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .clickable { com.mlmvpn.scanner.engines.amnezia.AmzEngine.undoDelete() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}
