package com.mlmvpn.scanner.ui.configstudio.parts

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.api.StudioHttpApi
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.data.studio.config.ConfigShape
import com.mlmvpn.scanner.data.studio.config.LocationConfigs
import com.mlmvpn.scanner.data.studio.domain.ExitCountries
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.ui.configstudio.design.CountryBadge
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.design.StudioSeparator
import com.mlmvpn.scanner.ui.configstudio.design.StudioType
import com.mlmvpn.scanner.ui.configstudio.faNum
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The countries an account can send somebody out of, and what is known about each one's server.
 *
 * Shared by every screen that gives people countries (new user, bulk, edit locations), so they cannot
 * disagree about which countries are ready. A public-pool country is checked ON THE ENGINE -- its
 * servers tested through themselves for where they really leave from (build 18, `POST /v1/pool/
 * verify`) -- when the operator picks it, two at a time, so picking eight countries does not start
 * eight slow tests at once.
 */
class CountryChoices internal constructor(
    val countries: List<String>,
    val status: Map<String, CountryStatus>,
    private val verifyFn: (String) -> Unit,
) {
    /** Make sure [cc] has been checked; a no-op for an own exit or a country already known. */
    fun verify(cc: String) = verifyFn(cc)
}

@Composable
fun rememberCountryChoices(context: Context, account: CloudAccount?, active: Boolean): CountryChoices {
    val scope = rememberCoroutineScope()
    var countries by remember(account?.id) { mutableStateOf<List<String>>(emptyList()) }
    var status by remember(account?.id) { mutableStateOf<Map<String, CountryStatus>>(emptyMap()) }
    val gate = remember { Semaphore(2) }

    LaunchedEffect(account?.id, active) {
        val acc = account ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        val api = StudioHttpApi(context, acc)
        val own = LocationConfigs.ownCountries(api).toSet()
        val pool = (api.getPool() as? StudioResult.Ok)?.value
        val poolCountries = if (pool?.enabled == true) pool.countries else emptyList()
        countries = (own + poolCountries).distinct().sorted()
        status = buildMap {
            own.forEach { put(it, CountryStatus.Own) }
            pool?.verified?.forEach { (cc, n) -> if (cc !in own && n > 0) put(cc, CountryStatus.Verified(n)) }
        }
    }

    val verify: (String) -> Unit = { cc ->
        val acc = account
        val known = status[cc]
        if (acc != null && (known == null || known == CountryStatus.None)) {
            status = status + (cc to CountryStatus.Checking)
            scope.launch {
                gate.withPermit {
                    val api = StudioHttpApi(context, acc)
                    val next = when (val r = api.verifyPool(cc)) {
                        is StudioResult.Ok ->
                            if (r.value.verified.isNotEmpty()) CountryStatus.Verified(r.value.verified.size) else CountryStatus.None
                        // An engine older than build 18 has no verify endpoint: the old one-off test.
                        is StudioResult.Err -> when (val t = api.testPool(cc)) {
                            is StudioResult.Ok -> if (t.value.ok) CountryStatus.Verified(1) else CountryStatus.None
                            is StudioResult.Err -> CountryStatus.None
                        }
                    }
                    status = status + (cc to next)
                }
            }
        }
    }
    return CountryChoices(countries, status, verify)
}

/**
 * What a create made, config by config: its protocol, where it leaves from, and -- for a country --
 * the address it was measured leaving from. The answer to «زمان ساخت کانفیگ ... به مدیر هم اعلام
 * نمی‌کنه»: nothing is created without the screen saying exactly what.
 */
@Composable
fun CreatedConfigsCard(made: List<LocationConfigs.Made>) {
    SettingsGroup {
        made.forEachIndexed { index, m ->
            if (index > 0) StudioSeparator()
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                        .background(protocolTint(m.shape).copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(StudioIcons.Config, contentDescription = null, tint = protocolTint(m.shape), modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        LocationConfigs.shortName(m.shape) + if (m.shape == ConfigShape.VLESS_XHTTP) "" else " · WebSocket",
                        color = Ios.Label,
                        fontSize = StudioType.Callout,
                        fontWeight = FontWeight.Medium,
                    )
                    val where = m.country?.let { ExitCountries.name(it) } ?: S(R.string.studio_created_direct)
                    Text(
                        where + (m.config.exitIp?.let { " · $it" } ?: ""),
                        color = Ios.SecondaryLabel,
                        fontSize = StudioType.Caption,
                    )
                }
                if (m.country != null) CountryBadge(m.country)
            }
        }
    }
}

/** One colour per protocol, used wherever a protocol is drawn. */
fun protocolTint(shape: ConfigShape) = when (shape) {
    ConfigShape.VLESS_WS -> androidx.compose.ui.graphics.Color(0xFF8B5CF6)
    ConfigShape.TROJAN_WS -> androidx.compose.ui.graphics.Color(0xFFBF5AF2)
    ConfigShape.VLESS_XHTTP -> androidx.compose.ui.graphics.Color(0xFFFF9F0A)
}

/** «این کاربر ۶ سرور می‌گیرد» -- how many entries the person's app will list, before creating. */
@Composable
fun LinkCountLine(shapes: Int, countries: Int, includeDirect: Boolean, ports: Int) {
    val n = LocationConfigs.linkCount(shapes, countries, includeDirect, ports)
    SettingsGroup {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(StudioIcons.Info, contentDescription = null, tint = Ios.SecondaryLabel, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                S(R.string.studio_link_count).replace("%1\$s", faNum(n)),
                color = Ios.Label,
                fontSize = StudioType.Subhead,
            )
        }
    }
}
