package com.mlmvpn.scanner.ui.tunnel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.core.warp.Ipv4Exits
import com.mlmvpn.core.warp.Nat64Exits
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.settings.Ios

/**
 * The WARP tile the user tapped did not come up in its budget, and the service connected with
 * another member (core/tunnel/WarpLadder): said here, on the page the user is looking at, with
 * whether the exit country was kept. The desktop says the same in a toast and opens the other
 * engine's window; on the phone the session stays on this page, which can disconnect it.
 */
@Composable
internal fun LadderHandOverCard(from: Transport, to: Transport, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val country = remember(from, to) {
        when {
            Ipv4Exits.isOn(context) -> Ipv4Exits.load(context).country.ifBlank { null }
            else -> Nat64Exits.selectedCountry(context)
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Ios.Blue.copy(alpha = 0.14f))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SwapHoriz, null, tint = Ios.Blue, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                tr("وصل شد با «${to.labelFa}»", "Connected with ${to.label}"),
                color = Ios.Label, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (country != null) {
                tr("«${from.labelFa}» روی این اینترنت به‌موقع بالا نیامد؛ برنامه خودش با «${to.labelFa}» وصل کرد — با همان کشور خروجی (${CountryLabel.withFlag(country)}). هر دو از شبکهٔ وارپ کلادفلرند.",
                    "${from.label} did not come up on this internet in time, so the app connected with ${to.label} -- with the same exit country (${CountryLabel.withFlag(country)}). Both are Cloudflare WARP.")
            } else {
                tr("«${from.labelFa}» روی این اینترنت به‌موقع بالا نیامد؛ برنامه خودش با «${to.labelFa}» وصل کرد. هر دو از شبکهٔ وارپ کلادفلرند و دفعهٔ بعد روی همین اینترنت مستقیم «${to.labelFa}» امتحان می‌شود.",
                    "${from.label} did not come up on this internet in time, so the app connected with ${to.label}. Both are Cloudflare WARP, and next time on this internet ${to.label} is tried first.")
            },
            color = Ios.SecondaryLabel, fontSize = 13.sp, lineHeight = 20.sp,
        )
    }
}
