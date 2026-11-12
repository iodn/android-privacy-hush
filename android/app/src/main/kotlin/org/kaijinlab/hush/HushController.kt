package org.kaijinlab.hush

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import org.kaijinlab.hush.data.*
import org.kaijinlab.hush.privilege.PrivilegeMethod
import org.kaijinlab.hush.privacy.PrivacyManager
import org.kaijinlab.hush.root.RootManager
import org.kaijinlab.hush.service.PrivacyMonitorService
import org.kaijinlab.hush.util.*
import org.kaijinlab.hush.widget.HushWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Native settings remain authoritative, including when Flutter is not running. */
class HushController(private val activity: android.app.Activity) {
    private val context = activity.applicationContext
    private val prefs = PreferenceManager.getInstance(context)
    private val root = RootManager.getInstance(Unit)
    private val logger = DebugLogHelper.getInstance(context)
    private var states: Map<PrivacyFeature, FeatureState> = emptyMap()

    private companion object {
        // The backends that can read Google Maps' own usage for the exception rule; shared by
        // the snapshot flag and the setter's require so the two cannot drift apart.
        val MAPS_PRIVILEGE_METHODS = setOf(PrivilegeMethod.ROOT, PrivilegeMethod.SHIZUKU, PrivilegeMethod.SUI)
    }

    suspend fun handle(method: String, arguments: Any?): Any? {
        val args = (arguments as? Map<*, *>) ?: emptyMap<Any, Any>()
        return when (method) {
            "appearance" -> prefs.getRawPreferences().getString("hush_theme", "system")
            "snapshot" -> snapshot(args["readStates"] == true)
            "authorize" -> {
                root.initialize(context)
                root.forceRootPermissionRequest()
                snapshot(true)
            }
            "set" -> {
                set(args)
                snapshot(false)
            }
            "apps" -> withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                context.packageManager.getInstalledApplications(0)
                    .filter { it.packageName != context.packageName }
                    .map { mapOf("id" to it.packageName, "name" to it.loadLabel(context.packageManager).toString()) }
                    .sortedBy { it["name"]?.lowercase() }
            }
            "exempt" -> {
                val ids = (args["ids"] as? List<*>)?.map { requireNotNull(it as? String) } ?: error("Missing apps")
                prefs.setExemptApps(ids.toSet())
                snapshot(false)
            }
            "open" -> { open(requireNotNull(args["target"] as? String)); null }
            "logs" -> withContext(Dispatchers.IO) {
                val file = logger.getLogFileForShare()
                if (file.exists()) file.readText() else ""
            }
            "clearLogs" -> withContext(Dispatchers.IO) { logger.clearLogs(); null }
            "shareLogs" -> {
                val intent = logger.createShareIntent() ?: error("No logs to share")
                activity.startActivity(Intent.createChooser(intent, "Share logs"))
                null
            }
            else -> error("Unknown action")
        }
    }

    private suspend fun snapshot(readStates: Boolean): Map<String, Any?> {
        root.initialize(context)
        val granted = root.isRootGranted()
        if (granted) {
            if (!PrivacyMonitorService.isRunning()) PrivacyMonitorService.start(context)
            if (readStates) states = PrivacyManager.getInstance(context).getCurrentStatus()
        } else states = emptyMap()
        return mapOf(
            "enabled" to prefs.isGlobalPrivacyEnabled,
            "mapsSupported" to (root.getPrivilegeMethod() in MAPS_PRIVILEGE_METHODS),
            "mapsUnverified" to prefs.getRawPreferences().getBoolean(org.kaijinlab.hush.util.MapsConnectivityGuard.WARNING_KEY, false),
            "granted" to granted,
            "available" to root.isRootAvailable(),
            "method" to root.getPrivilegeMethod().getDisplayName(),
            "running" to PrivacyMonitorService.isRunning(),
            "battery" to BatteryOptimizationManager(context).isIgnoringBatteryOptimizations(),
            "exactAlarms" to DeferredActionWork.canScheduleExactAlarms(context),
            "oemAutostart" to OemAutostartHelper.hasKnownPage,
            "charging" to ((context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0),
            "notifications" to NotificationManagerCompat.from(context).areNotificationsEnabled(),
            "bluetoothPermission" to (Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED),
            "usageAccess" to ForegroundAppDetector(context).hasUsageAccess(),
            "accessibilityGranted" to AccessibilityServiceManager.isAccessibilityServiceEnabled(context),
            "accessibility" to prefs.accessibilityServiceEnabled,
            "lockDelay" to prefs.lockDelaySeconds,
            "unlockDelay" to prefs.unlockDelaySeconds,
            "countdown" to prefs.showCountdown,
            "keepOff" to prefs.blockWhileLocked,
            "nfcRetry" to prefs.samsungNfcAutoRetry,
            "debugLogs" to prefs.debugLogsEnabled,
            "debugNotifications" to prefs.debugNotificationsEnabled,
            "theme" to prefs.getRawPreferences().getString("hush_theme", "system"),
            "exemptApps" to prefs.getExemptApps().toList(),
            "features" to PrivacyFeature.values().map { feature ->
                mapOf(
                    "id" to feature.name,
                    "name" to feature.displayName,
                    "state" to (states[feature] ?: FeatureState.UNKNOWN).name,
                    "reason" to unsupportedReason(feature),
                    "fixedUnlock" to root.fixedAtUnlock(feature),
                    "lock" to prefs.getFeatureDisableOnLock(feature),
                    "unlock" to prefs.getFeatureEnableOnUnlock(feature),
                    "unused" to prefs.getFeatureOnlyIfUnused(feature),
                    "maps" to prefs.getFeatureKeepForMaps(feature),
                    "notEnabled" to prefs.getFeatureOnlyIfNotEnabled(feature),
                    "owned" to prefs.getFeatureOnlyIfNotManual(feature),
                    "leftOn" to (feature in prefs.sensorsLeftOnAtLastLock),
                    "lockDelay" to prefs.getFeatureDelayOverride(feature, true),
                    "unlockDelay" to prefs.getFeatureDelayOverride(feature, false)
                )
            }
        )
    }

    private fun unsupportedReason(feature: PrivacyFeature): String? {
        if (feature in PrivacyFeature.getSensorFeatures() && !DeviceDetector.supportsSensorPrivacyToggle()) return "Requires Android 12"
        val hardware = when (feature) {
            PrivacyFeature.WIFI -> PackageManager.FEATURE_WIFI
            PrivacyFeature.BLUETOOTH -> PackageManager.FEATURE_BLUETOOTH
            PrivacyFeature.NFC -> PackageManager.FEATURE_NFC
            PrivacyFeature.CAMERA -> PackageManager.FEATURE_CAMERA_ANY
            PrivacyFeature.MICROPHONE -> PackageManager.FEATURE_MICROPHONE
            PrivacyFeature.LOCATION -> PackageManager.FEATURE_LOCATION
            PrivacyFeature.MOBILE_DATA -> PackageManager.FEATURE_TELEPHONY
            else -> null
        }
        if (hardware != null && !context.packageManager.hasSystemFeature(hardware)) return "Unavailable on this device"
        return root.unsupportedReason(feature)
    }

    internal suspend fun set(args: Map<*, *>) {
        val key = requireNotNull(args["key"] as? String)
        val value = args["value"]
        val featureName = args["feature"] as? String
        if (featureName != null) {
            val feature = PrivacyFeature.valueOf(featureName)
            require(unsupportedReason(feature) == null) { "Feature unavailable" }
            if (key == "lockDelay" || key == "unlockDelay") {
                require(key != "unlockDelay" || root.fixedAtUnlock(feature) == null) { "Managed by this privilege method" }
                val seconds = (value as? Number)?.toInt() ?: error("Invalid delay")
                prefs.setFeatureDelayOverride(feature, key == "lockDelay", seconds.takeUnless { it == -1 })
                return
            }
            val enabled = requireNotNull(value as? Boolean)
            when (key) {
                "lock" -> prefs.setFeatureDisableOnLock(feature, enabled)
                "unlock" -> {
                    require(root.fixedAtUnlock(feature) == null) { "Managed by this privilege method" }
                    prefs.setFeatureEnableOnUnlock(feature, enabled)
                }
                "unused" -> prefs.setFeatureOnlyIfUnused(feature, enabled)
                "maps" -> {
                    require(!enabled || root.getPrivilegeMethod() in MAPS_PRIVILEGE_METHODS) { "Google Maps exception requires Root or Shizuku" }
                    prefs.setFeatureKeepForMaps(feature, enabled)
                }
                "notEnabled" -> prefs.setFeatureOnlyIfNotEnabled(feature, enabled)
                "owned" -> prefs.setFeatureOnlyIfNotManual(feature, enabled)
                else -> error("Unknown feature setting")
            }
            return
        }
        when (key) {
            "enabled" -> {
                val enabled = requireNotNull(value as? Boolean)
                if (enabled) {
                    root.initialize(context)
                    require(root.isRootGranted()) { "Grant privileged access first" }
                    PrivacyMonitorService.start(context)
                }
                prefs.isGlobalPrivacyEnabled = enabled
                HushWidget.updateAllWidgets(context)
            }
            "lockDelay", "unlockDelay" -> {
                val seconds = (value as? Number)?.toInt() ?: error("Invalid delay")
                require(seconds in 0..TimerSettings.MAX_DELAY_SECONDS) { "Invalid delay" }
                if (key == "lockDelay") prefs.lockDelaySeconds = seconds else prefs.unlockDelaySeconds = seconds
            }
            "theme" -> {
                require(value in listOf("system", "light", "oled")) { "Invalid theme" }
                prefs.getRawPreferences().edit().putString("hush_theme", value as String).apply()
            }
            else -> {
                val enabled = requireNotNull(value as? Boolean)
                when (key) {
                    "countdown" -> prefs.showCountdown = enabled
                    "keepOff" -> prefs.blockWhileLocked = enabled
                    "nfcRetry" -> prefs.samsungNfcAutoRetry = enabled
                    "debugLogs" -> {
                        prefs.debugLogsEnabled = enabled
                        if (enabled) logger.logSessionStart()
                    }
                    "debugNotifications" -> prefs.debugNotificationsEnabled = enabled
                    "accessibility" -> prefs.accessibilityServiceEnabled = enabled
                    else -> error("Unknown setting")
                }
            }
        }
    }

    private fun open(target: String) {
        when (target) {
            "notifications" -> if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20)
            } else launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            "bluetooth" -> if (Build.VERSION.SDK_INT >= 31) activity.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 21)
            "battery" -> launch(BatteryOptimizationManager(context).createBatteryOptimizationIntent() ?: Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            "exactAlarms" -> if (Build.VERSION.SDK_INT >= 31) launch(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            )
            "autostart" -> OemAutostartHelper.openSettings(context)
            "usage" -> launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}")))
            "accessibility" -> launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            "security" -> launch(Intent(Settings.ACTION_SECURITY_SETTINGS))
            "shizuku" -> launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/")))
            else -> error("Unknown destination")
        }
    }

    private fun launch(intent: Intent) { activity.startActivity(intent) }
}
