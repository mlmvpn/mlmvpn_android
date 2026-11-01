package com.mlmvpn.scanner.ui.mae

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.mlmvpn.scanner.engines.mae.model.ServiceDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app's own launcher icon when it is installed on this phone; otherwise a rounded tile with
 * its first letter in a colour derived from its name, so a list of apps and sites still reads at a
 * glance. Icons load off the main thread and are cached for the session.
 */
@Composable
fun MaeAppIcon(def: ServiceDef, label: String, size: Dp = 36.dp) {
    val context = LocalContext.current
    // At the size it is drawn on this screen: a fixed 96 px was blurry on dense screens.
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }.coerceAtLeast(48)
    val key = "${def.id}@$px"
    val icon by produceState<ImageBitmap?>(initialValue = cache[key], key) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            def.packages.firstNotNullOfOrNull { pkg ->
                runCatching { pm.getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap() }.getOrNull()
            }
        }?.also { cache[key] = it }
    }
    val shape = RoundedCornerShape(size * 0.24f)
    val current = icon
    if (current != null) {
        Image(bitmap = current, contentDescription = null, modifier = Modifier.size(size).clip(shape))
    } else {
        Box(
            modifier = Modifier.size(size).clip(shape).background(tint(def.id)),
            contentAlignment = Alignment.Center,
        ) {
            Text(label.trim().take(1).uppercase(), color = Color.White, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Bold)
        }
    }
}

private val cache = HashMap<String, ImageBitmap>()

private val PALETTE = listOf(
    Color(0xFF8B5CF6), Color(0xFF30D158), Color(0xFFFF9F0A), Color(0xFFFF375F),
    Color(0xFFBF5AF2), Color(0xFF64D2FF), Color(0xFFFF6482), Color(0xFF5E5CE6),
)

private fun tint(id: String) = PALETTE[Math.floorMod(id.hashCode(), PALETTE.size)]
