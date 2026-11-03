package org.kaijinlab.hush.privilege

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kaijinlab.hush.data.FeatureState
import org.kaijinlab.hush.data.PrivacyFeature
import org.kaijinlab.hush.util.PreferenceManager
import org.kaijinlab.hush.util.isDeviceSecurelyLocked
import org.kaijinlab.hush.util.isScreenCurrentlyLocked
import org.kaijinlab.hush.worker.PrivacyActionWorker

/** Root-only sensor restrictions remain usable after keyguard locks, without changing authentication. */
internal object RootSensorPolicy {
    private val mutex = Mutex()
    private val sensors = setOf(PrivacyFeature.CAMERA, PrivacyFeature.MICROPHONE)
    private fun name(feature: PrivacyFeature) = if (feature == PrivacyFeature.CAMERA) "camera" else "microphone"
    internal fun ownedKey(feature: PrivacyFeature) = "root_sensor_owned_${feature.name.lowercase()}"
    private fun prefs(context: Context) = PreferenceManager.getInstance(context).getRawPreferences()
    private fun command(context: Context, action: String, feature: PrivacyFeature, owners: Set<String> = emptySet()): String {
        val apk = "'" + context.applicationInfo.sourceDir.replace("'", "'\\''") + "'"
        require(owners.all { it.matches(Regex("[0-9]+:[0-9]+")) })
        val ids = if (owners.isEmpty()) "" else " " + owners.sorted().joinToString(",")
        return "CLASSPATH=$apk app_process /system/bin org.kaijinlab.hush.root.RootSensorCommand $action ${name(feature)}$ids"
    }
    private fun owned(context: Context, feature: PrivacyFeature): Set<String> =
        prefs(context).getStringSet(ownedKey(feature), emptySet()).orEmpty().toSet()
    private fun identities(value: String?): Set<String>? = when {
        value == "NONE" -> emptySet()
        value != null && value.matches(Regex("[0-9]+:[0-9]+(,[0-9]+:[0-9]+)*")) -> value.split(',').toSet()
        else -> null
    }
    private fun token(result: CommandResult): String? = if (result.success) result.output.lastOrNull()?.trim() else null

    suspend fun read(context: Context, feature: PrivacyFeature, run: suspend (String) -> CommandResult): FeatureState? {
        if (feature !in sensors) return null
        return when (token(run(command(context, "read", feature)))) {
            "BLOCKED" -> FeatureState.DISABLED
            "ALLOWED" -> FeatureState.ENABLED
            else -> FeatureState.UNKNOWN
        }
    }

    suspend fun apply(context: Context, feature: PrivacyFeature, enable: Boolean, run: suspend (String) -> CommandResult): CommandResult? {
        if (feature !in sensors) return null
        return mutex.withLock {
            val prefs = prefs(context)
            val key = ownedKey(feature)
            if (enable) {
                if (isDeviceSecurelyLocked(context, "hush-RootSensorPolicy")) {
                    return@withLock CommandResult.failure("Phone relocked before sensor restoration")
                }
                val owners = owned(context, feature)
                if (owners.isNotEmpty()) {
                    val result = run(command(context, "allow", feature, owners))
                    if (!result.success) return@withLock result
                    prefs.edit().remove(key).commit()
                }
                run("cmd sensor_privacy disable 0 ${name(feature)}")
            } else {
                val targets = identities(token(run(command(context, "targets", feature))))
                    ?: return@withLock CommandResult.failure("Could not read sensor restrictions for user profiles")
                if (targets.isEmpty()) return@withLock CommandResult.success()
                // Record ownership durably BEFORE a policy change that outlives this process.
                if (!prefs.edit().putStringSet(key, owned(context, feature) + targets).commit()) return@withLock CommandResult.failure("Could not save sensor recovery state")
                run(command(context, "block", feature, targets))
            }
        }
    }

    /**
     * Replace our administrative blocks with ordinary sensor privacy after unlock. This keeps
     * access blocked until the configured unlock action, while returning manual control to QS.
     * The helper verifies sensor privacy BEFORE releasing the restriction; a failure retains it.
     */
    suspend fun handoffAfterUnlock(context: Context, run: suspend (String) -> CommandResult) {
        if (sensors.none { owned(context, it).isNotEmpty() }) return
        if (isScreenCurrentlyLocked(context, "hush-RootSensorPolicy") ||
            PrivacyActionWorker.lastLockAtMillis > PrivacyActionWorker.lastUnlockAtMillis) return
        mutex.withLock {
            for (feature in sensors) {
                val owners = owned(context, feature)
                if (owners.isEmpty()) continue
                val base = identities(token(run(command(context, "base", feature, owners)))) ?: continue
                if (base.isEmpty() || run(command(context, "handoff", feature, base)).success) {
                    prefs(context).edit().remove(ownedKey(feature)).commit()
                }
            }
        }
    }
}
