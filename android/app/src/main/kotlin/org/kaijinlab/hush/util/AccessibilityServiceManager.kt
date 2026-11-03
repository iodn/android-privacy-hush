package org.kaijinlab.hush.util

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

object AccessibilityServiceManager {
    
    private const val TAG = "hush-AccessibilityManager"
    
    /**
     * Check if the Hush Accessibility Service is currently enabled.
     * 
     * @param context Application context
     * @return true if service is enabled, false otherwise
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            
            val component = android.content.ComponentName(context, org.kaijinlab.hush.accessibility.PrivacyAccessibilityService::class.java)
            val isEnabled = enabledServices?.split(':')?.any {
                android.content.ComponentName.unflattenFromString(it) == component
            } == true
            
            Log.d(TAG, "Accessibility service enabled check: $isEnabled")
            isEnabled
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility service status", e)
            false
        }
    }
    
    /**
     * Open Android system Accessibility Settings where user can enable the service.
     * 
     * @param context Application context
     */
    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.i(TAG, "Opened Accessibility Settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open accessibility settings", e)
        }
    }
}
