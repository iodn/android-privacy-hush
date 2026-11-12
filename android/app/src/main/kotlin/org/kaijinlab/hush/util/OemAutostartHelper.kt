package org.kaijinlab.hush.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * OEM battery managers can block background starts even after the standard
 * battery-optimization exemption is granted, and each vendor keeps its own settings
 * page with no stable class name across ROM versions. Known pages are tried in
 * order; the system App-info screen is the fallback, so the entry point never
 * silently does nothing.
 */
object OemAutostartHelper {

    // The vendor pages known for this manufacturer, most likely first. Empty means
    // no known page: the UI hides the entry rather than promising a settings screen.
    internal fun components(manufacturer: String): List<ComponentName> =
        when (manufacturer.lowercase()) {
            "xiaomi", "redmi", "poco" -> listOf(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            )
            "huawei", "honor" -> listOf(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
            )
            "oppo", "realme", "oneplus" -> listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity")
            )
            "vivo" -> listOf(
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            )
            "samsung" -> listOf(
                ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                ComponentName("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity")
            )
            else -> emptyList()
        }

    val hasKnownPage: Boolean get() = components(Build.MANUFACTURER).isNotEmpty()

    /** @return true if a vendor page opened; false if only the system App-info screen could. */
    fun openSettings(context: Context): Boolean {
        for (component in components(Build.MANUFACTURER)) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN).setComponent(component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return true
            } catch (e: Exception) {
                // Page moved or absent on this ROM - try the next one.
            }
        }
        return try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            false
        } catch (e: Exception) {
            false
        }
    }
}
