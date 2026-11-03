package org.kaijinlab.hush.util

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.kaijinlab.hush.data.PrivacyFeature
import org.kaijinlab.hush.worker.PrivacyActionWorker
import java.util.concurrent.TimeUnit

/** Durable timers are scoped to one observed lock/unlock cycle, including across process death. */
object DeferredActionWork {
    const val KEY_FEATURES = "deferred_features"
    const val KEY_GENERATION = "deferred_generation"
    private const val OBSERVED_LOCK = "last_observed_lock"

    fun tag(locking: Boolean): String = if (locking) "hush_deferred_lock" else "hush_deferred_unlock"

    fun generation(context: Context): Long = PreferenceManager.getInstance(context).getRawPreferences().getLong(KEY_GENERATION, 0)

    @Synchronized
    fun recordState(context: Context, locking: Boolean) {
        val prefs = PreferenceManager.getInstance(context).getRawPreferences()
        if (!prefs.contains(OBSERVED_LOCK) || prefs.getBoolean(OBSERVED_LOCK, !locking) != locking) {
            prefs.edit().putLong(KEY_GENERATION, generation(context) + 1).putBoolean(OBSERVED_LOCK, locking).commit()
        }
    }

    @Synchronized
    fun generationForState(context: Context, locking: Boolean): Long? {
        val prefs = PreferenceManager.getInstance(context).getRawPreferences()
        if (prefs.contains(OBSERVED_LOCK) && prefs.getBoolean(OBSERVED_LOCK, !locking) != locking) return null
        return generation(context)
    }

    @Synchronized
    fun newBoot(context: Context) {
        PreferenceManager.getInstance(context).getRawPreferences().edit()
            .putLong(KEY_GENERATION, generation(context) + 1).remove(OBSERVED_LOCK).commit()
        WorkManager.getInstance(context).cancelAllWorkByTag(tag(true))
        WorkManager.getInstance(context).cancelAllWorkByTag(tag(false))
    }

    fun buildRequest(features: Set<PrivacyFeature>, locking: Boolean, delayMillis: Long, generation: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<PrivacyActionWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .addTag(tag(locking))
            .setInputData(workDataOf(
                PrivacyActionWork.KEY_IS_LOCKING to locking,
                PrivacyActionWork.KEY_IS_DEVICE_LOCKED to false,
                PrivacyActionWork.KEY_TRIGGER to "deferred",
                KEY_FEATURES to features.joinToString(",") { it.name },
                KEY_GENERATION to generation
            )).build()

    @Synchronized
    fun enqueue(context: Context, features: Set<PrivacyFeature>, locking: Boolean, delayMillis: Long, generation: Long) {
        // recordState/newBoot persist the generation before work can be queued.
        val name = "${tag(locking)}_${generation}_${features.map { it.name }.sorted().joinToString("_")}"
        WorkManager.getInstance(context).enqueueUniqueWork(
            name, ExistingWorkPolicy.KEEP, buildRequest(features, locking, delayMillis, generation)
        )
    }
}
