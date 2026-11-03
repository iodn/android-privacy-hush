package org.kaijinlab.hush.root

import android.content.Context
import android.util.Log
import org.kaijinlab.hush.privilege.CommandResult
import org.kaijinlab.hush.privilege.PrivilegeManager
import org.kaijinlab.hush.privilege.PrivilegeMethod
import org.kaijinlab.hush.util.LogManager
import org.kaijinlab.hush.util.SingletonHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RootManager private constructor() {

    companion object : SingletonHolder<RootManager, Unit>({ RootManager() }) {
        private const val TAG = "hush-RootManager"
    }

    private var logManager: LogManager? = null
    private var privilegeManager: PrivilegeManager? = null
    private var context: Context? = null

    suspend fun initialize(context: Context) {
        this.context = context
        logManager = LogManager.getInstance(context)
        privilegeManager = PrivilegeManager.getInstance(context)
        privilegeManager?.initialize()
        if (getPrivilegeMethod() == PrivilegeMethod.ROOT) {
            org.kaijinlab.hush.privilege.RootSensorPolicy.handoffAfterUnlock(context, ::executeCommand)
        }
    }

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            return@withContext privilegeManager?.isPrivilegeAvailable() ?: false
        } catch (e: Exception) {
            return@withContext false
        }
    }

    suspend fun isRootGranted(): Boolean = withContext(Dispatchers.IO) {
        try {
            val granted = privilegeManager?.isPermissionGranted() ?: false
            Log.d(TAG, "isRootGranted() - privilegeManager.isPermissionGranted() returned: $granted")
            return@withContext granted
        } catch (e: Exception) {
            Log.e(TAG, "isRootGranted() - exception: ${e.message}")
            return@withContext false
        }
    }

    suspend fun executeCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            return@withContext privilegeManager?.executeCommand(command)
                ?: CommandResult.failure("Privilege manager not initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command: $command", e)
            return@withContext CommandResult.failure(e.message ?: "Unknown error")
        }
    }

    suspend fun executeWithFallbacks(commands: List<String>): CommandResult = withContext(Dispatchers.IO) {
        try {
            return@withContext privilegeManager?.executeWithFallbacks(commands)
                ?: CommandResult.failure("Privilege manager not initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing commands with fallbacks", e)
            return@withContext CommandResult.failure(e.message ?: "Unknown error")
        }
    }

    /**
     * @return null when the current backend has no API route for this feature, so the caller
     *         should fall back to its shell commands.
     */
    suspend fun setFeatureState(
        feature: org.kaijinlab.hush.data.PrivacyFeature,
        enable: Boolean
    ): CommandResult? = withContext(Dispatchers.IO) {
        try {
            privilegeManager?.setFeatureState(feature, enable)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // null, not a failure: a non-null result stops the caller falling back to its shell
            // commands, so a throw here would report a failure without ever trying them.
            Log.e(TAG, "Error setting ${feature.displayName} through the backend API", e)
            null
        }
    }

    suspend fun readFeatureState(
        feature: org.kaijinlab.hush.data.PrivacyFeature
    ): org.kaijinlab.hush.data.FeatureState? = withContext(Dispatchers.IO) {
        try {
            privilegeManager?.readFeatureState(feature)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error reading ${feature.displayName} through the backend API", e)
            null
        }
    }

    suspend fun forceRootPermissionRequest(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "forceRootPermissionRequest() - calling privilegeManager.requestPermission()...")
            val granted = privilegeManager?.requestPermission() ?: false
            Log.d(TAG, "forceRootPermissionRequest() - privilegeManager.requestPermission() returned: $granted")

            // Double-check the actual state (this is the source of truth)
            val actualGranted = privilegeManager?.isPermissionGranted() ?: false
            Log.d(TAG, "forceRootPermissionRequest() - double-check: privilegeManager.isPermissionGranted() = $actualGranted")

            // Use the double-check value as it's more reliable
            // requestPermission() might return false due to timing, but permission could still be granted
            if (granted != actualGranted) {
                Log.w(TAG, "forceRootPermissionRequest() - MISMATCH: requestPermission=$granted, isPermissionGranted=$actualGranted - using actualGranted")
            }

            return@withContext actualGranted
        } catch (e: Exception) {
            Log.e(TAG, "forceRootPermissionRequest() - error: ${e.message}", e)
            return@withContext false
        }
    }

    fun getPrivilegeMethod(): PrivilegeMethod {
        return privilegeManager?.getCurrentMethod() ?: PrivilegeMethod.NONE
    }

    fun unsupportedReason(feature: org.kaijinlab.hush.data.PrivacyFeature): String? =
        privilegeManager?.unsupportedReason(feature)

    fun fixedAtUnlock(feature: org.kaijinlab.hush.data.PrivacyFeature): Boolean? =
        privilegeManager?.fixedAtUnlock(feature)

}

