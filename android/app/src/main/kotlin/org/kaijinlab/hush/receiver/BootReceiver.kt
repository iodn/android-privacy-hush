package org.kaijinlab.hush.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.kaijinlab.hush.service.PrivacyMonitorService
import org.kaijinlab.hush.util.Constants

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "hush-BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Boot broadcast received: ${intent.action}")
        
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.i(TAG, "Device boot completed - checking service restart")
                org.kaijinlab.hush.util.DeferredActionWork.newBoot(context)
                handleBootCompleted(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> handleBootCompleted(context)
            
            else -> {
                Log.w(TAG, "Unexpected boot intent action: ${intent.action}")
            }
        }
    }
    
    private fun handleBootCompleted(context: Context) {
        try {
            // Apply 300ms debounce to prevent race conditions as requested
            val pending = goAsync()
            Handler(Looper.getMainLooper()).postDelayed({
                try { restartService(context) } finally { pending?.finish() }
            }, Constants.BootReceiver.DEBOUNCE_DELAY_MS)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle boot completed", e)
        }
    }
    
    private fun restartService(context: Context) {
        try {
            // The monitor service is always on: it is what applies the user's protection.
            Log.i(TAG, "Restarting PrivacyMonitorService")
            PrivacyMonitorService.start(context)
            Log.i(TAG, "Service restart requested after boot - will apply initial privacy state")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart service after boot", e)
        }
    }
}
