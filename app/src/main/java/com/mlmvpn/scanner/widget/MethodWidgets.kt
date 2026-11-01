package com.mlmvpn.scanner.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One home-screen widget per connection method.
 *
 * Android lists a widget in its picker per provider class, so each method has a class of its own --
 * that is what lets the user find «وایرگارد» by name in the phone's widget list, with its own
 * picture, instead of one generic widget and a question afterwards. All the behaviour is here, in
 * the base class; the subclasses only name their method.
 */
abstract class MethodWidgetProvider(private val method: String) : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        render(context, intArrayOf(appWidgetId))
    }

    private fun render(context: Context, ids: IntArray) {
        val app = context.applicationContext
        val pending = goAsync()
        WidgetUpdater.attach(app)
        scope.launch {
            try {
                WidgetUpdater.renderMethod(app, AppWidgetManager.getInstance(app), method, ids)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

class MasqueWidget : MethodWidgetProvider("masque")
class WireguardWidget : MethodWidgetProvider("wireguard")
class WarpOnWarpWidget : MethodWidgetProvider("warp_on_warp")
class WarpWidget : MethodWidgetProvider("warp")
class PsiphonWidget : MethodWidgetProvider("psiphon")
class TorWidget : MethodWidgetProvider("tor")
class GephWidget : MethodWidgetProvider("geph")
class LanternWidget : MethodWidgetProvider("lantern")
class UrnetworkWidget : MethodWidgetProvider("urnetwork")
class QuickWidget : MethodWidgetProvider("quick")
class MaeWidget : MethodWidgetProvider("mae")
class FluxWidget : MethodWidgetProvider("flux")
class FenixWidget : MethodWidgetProvider("fenix")
class AmneziaWidget : MethodWidgetProvider("amnezia")
class ProtonWidget : MethodWidgetProvider("proton")
class WindscribeWidget : MethodWidgetProvider("windscribe")
class OutlineWidget : MethodWidgetProvider("outline")
class NpvWidget : MethodWidgetProvider("npv")
class GithubWidget : MethodWidgetProvider("github")
class VpnGateWidget : MethodWidgetProvider("vpngate")
class OpenVpnWidget : MethodWidgetProvider("openvpn")
class V2rayWidget : MethodWidgetProvider("nodes")
class IranWidget : MethodWidgetProvider("iran")
class FrontingWidget : MethodWidgetProvider("fronting")
class AntiSanctionWidget : MethodWidgetProvider("antisanction")
class EmergencyGstWidget : MethodWidgetProvider("emergency_2")
class EmergencySniWidget : MethodWidgetProvider("emergency_3")

object MethodWidgets {

    /** Method id -> its provider. Two are declared in debug builds only (see the debug manifest). */
    val PROVIDERS: Map<String, Class<out MethodWidgetProvider>> = linkedMapOf(
        "masque" to MasqueWidget::class.java,
        "wireguard" to WireguardWidget::class.java,
        "warp_on_warp" to WarpOnWarpWidget::class.java,
        "warp" to WarpWidget::class.java,
        "psiphon" to PsiphonWidget::class.java,
        "tor" to TorWidget::class.java,
        "geph" to GephWidget::class.java,
        "lantern" to LanternWidget::class.java,
        "urnetwork" to UrnetworkWidget::class.java,
        "quick" to QuickWidget::class.java,
        "mae" to MaeWidget::class.java,
        "flux" to FluxWidget::class.java,
        "fenix" to FenixWidget::class.java,
        "amnezia" to AmneziaWidget::class.java,
        "proton" to ProtonWidget::class.java,
        "windscribe" to WindscribeWidget::class.java,
        "outline" to OutlineWidget::class.java,
        "npv" to NpvWidget::class.java,
        "github" to GithubWidget::class.java,
        "vpngate" to VpnGateWidget::class.java,
        "openvpn" to OpenVpnWidget::class.java,
        "nodes" to V2rayWidget::class.java,
        "iran" to IranWidget::class.java,
        "fronting" to FrontingWidget::class.java,
        "antisanction" to AntiSanctionWidget::class.java,
        "emergency_2" to EmergencyGstWidget::class.java,
        "emergency_3" to EmergencySniWidget::class.java,
    )

    /** Whether the launcher can be asked to place a widget for the user (Android 8+, and it agrees). */
    fun canPin(context: Context): Boolean = Build.VERSION.SDK_INT >= 26 &&
        runCatching { AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported }.getOrDefault(false)

    /**
     * Asks the launcher to place [method]'s widget; it shows its own confirmation. False when the
     * launcher cannot, or this build has no such widget -- the caller then explains the manual way.
     */
    fun requestPin(context: Context, method: String): Boolean {
        if (Build.VERSION.SDK_INT < 26 || !canPin(context)) return false
        val cls = PROVIDERS[method] ?: return false
        if (WidgetMethods.app(method) == null) return false
        return runCatching {
            AppWidgetManager.getInstance(context).requestPinAppWidget(ComponentName(context, cls), null, null)
        }.getOrDefault(false)
    }

    /** How many of [method]'s widgets are on the home screen. */
    fun placedCount(context: Context, method: String): Int {
        val cls = PROVIDERS[method] ?: return 0
        return runCatching { AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, cls)).size }.getOrDefault(0)
    }
}
