package org.kaijinlab.hush.util

import android.content.Context
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.OutOfQuotaPolicy
import org.kaijinlab.hush.data.PrivacyFeature
import org.kaijinlab.hush.worker.PrivacyActionWorker
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.kaijinlab.hush.receiver.PrivacyTimerReceiver

/** Durable timers are scoped to one observed lock/unlock cycle, including across process death. */
object DeferredActionWork {
    const val KEY_FEATURES = "deferred_features"
    const val KEY_GENERATION = "deferred_generation"
    private const val OBSERVED_LOCK = "last_observed_lock"
    private const val ALARMS = "privacy_timer_names"
    private val wakeLocks = ConcurrentHashMap<UUID, PowerManager.WakeLock>()

    fun canScheduleExactAlarms(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun alarmIntent(context: Context, name: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, PrivacyTimerReceiver::class.java)
            .setData(Uri.Builder().scheme("hush").authority("timer").appendPath(name).build()),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    @Synchronized
    fun cancelTimers(context: Context, locking: Boolean? = null) {
        val prefs = PreferenceManager.getInstance(context).getRawPreferences()
        val names = prefs.getStringSet(ALARMS, emptySet()).orEmpty().toSet()
        val removed = names.filter { locking == null || it.startsWith(tag(locking) + "_") }.toSet()
        val edit = prefs.edit().putStringSet(ALARMS, names - removed)
        for (name in removed) {
            val alarm = alarmIntent(context, name)
            context.getSystemService(AlarmManager::class.java).cancel(alarm)
            alarm.cancel()
            edit.remove(name).remove("${name}_delivered")
        }
        edit.commit()
        org.kaijinlab.hush.service.PrivacyMonitorService.refreshTimerCountdown()
    }

    fun releaseWakeLock(id: UUID) {
        wakeLocks.remove(id)?.let { if (it.isHeld) it.release() }
    }

    @Synchronized
    fun dispatchAlarm(context: Context, name: String) {
        val prefs = PreferenceManager.getInstance(context).getRawPreferences()
        if (name !in prefs.getStringSet(ALARMS, emptySet()).orEmpty() || prefs.getBoolean("${name}_delivered", false)) return
        val locking = when {
            name.startsWith(tag(true) + "_") -> true
            name.startsWith(tag(false) + "_") -> false
            else -> return
        }
        val suffix = name.removePrefix(tag(locking) + "_")
        val expected = suffix.substringBefore('_').toLongOrNull() ?: return
        if (expected != generation(context)) return
        val encoded = suffix.substringAfter('_', "")
        val selected = encoded.split(',').mapNotNull { value ->
            PrivacyFeature.values().firstOrNull { it.name == value }
        }.toSet()
        if (selected.isEmpty()) return
        val request = buildRequest(selected, locking, 0, expected).let {
            OneTimeWorkRequestBuilder<PrivacyActionWorker>()
                .setInputData(it.workSpec.input).addTag(tag(locking))
                .apply {
                    // Earlier releases emulate expedited work with a second foreground service.
                    // The monitor service and bounded wake lock already cover execution there.
                    if (Build.VERSION.SDK_INT >= 31) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }.build()
        }
        // Alarm delivery wakes the CPU only for the receiver. Bridge that wake-up to the worker,
        // including GreedyScheduler runs, and release on completion or after a bounded timeout.
        val lock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hush:privacy-timer")
        lock.setReferenceCounted(false)
        lock.acquire(60_000L)
        wakeLocks[request.id] = lock
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ releaseWakeLock(request.id) }, 60_000L)
        try {
            val operation = WorkManager.getInstance(context).enqueueUniqueWork(name + "_due", ExistingWorkPolicy.KEEP, request)
            prefs.edit().putBoolean("${name}_delivered", true).commit()
            operation.result.addListener({
                try {
                    operation.result.get()
                } catch (error: Exception) {
                    releaseWakeLock(request.id)
                    prefs.edit().remove("${name}_delivered").commit()
                    android.util.Log.e("hush-Timer", "Could not queue the timer action", error)
                }
            }, java.util.concurrent.Executor { it.run() })
        } catch (error: Exception) {
            releaseWakeLock(request.id)
            throw error
        }
        org.kaijinlab.hush.service.PrivacyMonitorService.refreshTimerCountdown()
    }

    fun nextDeadline(context: Context): Pair<Boolean, Long>? {
        val prefs = PreferenceManager.getInstance(context).getRawPreferences()
        return prefs.getStringSet(ALARMS, emptySet()).orEmpty()
            .filterNot { prefs.getBoolean("${it}_delivered", false) }
            .map { it.startsWith(tag(true) + "_") to prefs.getLong(it, 0) }
            .minByOrNull { it.second }
    }

    fun tag(locking: Boolean): String = if (locking) "hush_deferred_lock" else "hush_deferred_unlock"

    fun generation(context: Context): Long = PreferenceManager.getInstance(context).getRawPreferences().getLong(KEY_GENERATION, 0)

    @Synchronized
    fun recordState(context: Context, locking: Boolean) {
        val prefs = PreferenceManager.getInstance(context).getRawPreferences()
        if (!prefs.contains(OBSERVED_LOCK) || prefs.getBoolean(OBSERVED_LOCK, !locking) != locking) {
            cancelTimers(context)
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
        cancelTimers(context)
        PreferenceManager.getInstance(context).getRawPreferences().edit()
            .putLong(KEY_GENERATION, generation(context) + 1).remove(OBSERVED_LOCK).commit()
        WorkManager.getInstance(context).cancelAllWorkByTag(tag(true))
        WorkManager.getInstance(context).cancelAllWorkByTag(tag(false))
    }

    fun buildRequest(features: Set<PrivacyFeature>, locking: Boolean, delayMillis: Long, generation: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<PrivacyActionWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            // Best-effort fallback when exact-alarm access is unavailable. Doze can delay it.
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
        if (features.isEmpty() || generationForState(context, locking) != generation) return
        val name = "${tag(locking)}_${generation}_${features.map { it.name }.sorted().joinToString(",")}"
        if (delayMillis > 0 && canScheduleExactAlarms(context)) {
            val prefs = PreferenceManager.getInstance(context).getRawPreferences()
            if (prefs.getBoolean("${name}_delivered", false)) return
            val deadline = prefs.getLong(name, SystemClock.elapsedRealtime() + delayMillis)
            val names = prefs.getStringSet(ALARMS, emptySet()).orEmpty() + name
            prefs.edit().putLong(name, deadline).putStringSet(ALARMS, names).commit()
            try {
                context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, alarmIntent(context, name)
                )
                org.kaijinlab.hush.service.PrivacyMonitorService.refreshTimerCountdown()
                return
            } catch (error: SecurityException) {
                android.util.Log.w("hush-Timer", "Exact-alarm access changed; using best-effort scheduling", error)
            }
        }
        WorkManager.getInstance(context).enqueueUniqueWork(
            name, ExistingWorkPolicy.KEEP, buildRequest(features, locking, delayMillis, generation)
        )
    }
}
