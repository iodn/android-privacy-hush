package org.kaijinlab.hush.util

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.kaijinlab.hush.data.PrivacyFeature
import org.kaijinlab.hush.worker.PrivacyActionWorker

/**
 * The one place that defines how a privacy action is handed to WorkManager: the input-data
 * contract, which unique name a direction uses, and the default REPLACE policy.
 *
 * Four call sites across three classes used to hand-write this, and the worker read the same
 * key strings back from its own copies of them, so a key renamed in one place and not the
 * others would have compiled and then silently delivered a default value at runtime.
 */
object PrivacyActionWork {

    const val KEY_IS_LOCKING = "is_locking"
    const val KEY_IS_DEVICE_LOCKED = "is_device_locked"
    const val KEY_TRIGGER = "trigger"
    const val KEY_REASON = "reason"
    const val KEY_TRIGGERED_AT = "triggered_at"
    // Per feature, SystemClock.elapsedRealtime() when a reblock first saw it back on: "WIFI=123,NFC=456".
    const val KEY_FOUND_ON_AT = "found_on_at"

    const val TRIGGER_SERVICE_INIT = "service_init"
    const val TRIGGER_REBLOCK = "reblock"

    // Delayed features have separate durable timers; reblocking must not bring them forward.
    fun usesSplitReblock(prefs: PreferenceManager): Boolean =
        prefs.hasCustomFeatureDelays(true) || prefs.lockDelaySeconds > 0

    fun configuredFeatures(prefs: PreferenceManager, locking: Boolean): List<PrivacyFeature> =
        PrivacyFeature.values().filter {
            if (locking) prefs.getFeatureDisableOnLock(it) else prefs.getFeatureEnableOnUnlock(it)
        }.filter {
            locking || prefs.isGlobalPrivacyEnabled ||
                if (it in PrivacyFeature.getSystemModeFeatures()) prefs.getFeatureEnabledByApp(it)
                else it in prefs.featuresOffSinceUnlock
        }

    /**
     * Lock work and unlock work each have one unique name, so a newer action of the same
     * direction REPLACEs the older one rather than running alongside it, unless the caller KEEPs it.
     */
    fun workName(isLocking: Boolean, isReblock: Boolean = false): String =
        if (isLocking && isReblock) Constants.Work.NAME_REBLOCK
        else if (isLocking) Constants.Work.NAME_LOCK else Constants.Work.NAME_UNLOCK

    fun buildRequest(
        isLocking: Boolean,
        isDeviceLocked: Boolean,
        trigger: String,
        reason: String,
        foundOnAt: Map<PrivacyFeature, Long> = emptyMap(),
        triggeredAt: Long? = null
    ): OneTimeWorkRequest = OneTimeWorkRequestBuilder<PrivacyActionWorker>()
        .setInputData(
            workDataOf(
                KEY_IS_LOCKING to isLocking,
                KEY_IS_DEVICE_LOCKED to isDeviceLocked,
                KEY_TRIGGER to trigger,
                KEY_REASON to reason,
                KEY_TRIGGERED_AT to triggeredAt,
                KEY_FOUND_ON_AT to foundOnAt.entries.joinToString(",") { "${it.key.name}=${it.value}" }
            )
        )
        .build()

    /**
     * @return the unique work name used, so the caller can log which one it replaced.
     */
    fun enqueue(
        context: Context,
        isLocking: Boolean,
        isDeviceLocked: Boolean,
        trigger: String,
        reason: String,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
        foundOnAt: Map<PrivacyFeature, Long> = emptyMap()
    ): String {
        val prefs = PreferenceManager.getInstance(context)
        val separateReblock = trigger == TRIGGER_REBLOCK && usesSplitReblock(prefs)
        val name = workName(isLocking, separateReblock)
        // Arm the OS before returning from SCREEN_OFF. The initial worker itself may be
        // dispatched late once the CPU sleeps, so it must not be the only place that arms timers.
        if (trigger != TRIGGER_REBLOCK && (!isLocking || prefs.isGlobalPrivacyEnabled)) {
            DeferredActionWork.generationForState(context, isLocking)?.let { generation ->
                configuredFeatures(prefs, isLocking).groupBy { prefs.getFeatureDelaySeconds(it, isLocking) }
                    .filterKeys { it > 0 }.forEach { (seconds, features) ->
                        DeferredActionWork.enqueue(context, features.toSet(), isLocking, seconds * 1000L, generation)
                    }
            }
        }
        WorkManager.getInstance(context).enqueueUniqueWork(
            name,
            policy,
            buildRequest(isLocking, isDeviceLocked, trigger, reason, foundOnAt, android.os.SystemClock.elapsedRealtime())
        )
        return name
    }

    fun foundOnAt(value: String?): Map<PrivacyFeature, Long> =
        value.orEmpty().split(",").mapNotNull { entry ->
            val (name, millis) = entry.split("=").takeIf { it.size == 2 } ?: return@mapNotNull null
            val feature = PrivacyFeature.values().firstOrNull { it.name == name } ?: return@mapNotNull null
            millis.toLongOrNull()?.let { feature to it }
        }.toMap()
}
