package org.kaijinlab.hush.util

import android.app.AlarmManager
import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.kaijinlab.hush.data.PrivacyFeature

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivacyWakeupTimerTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val prefs get() = PreferenceManager.getInstance(context).getRawPreferences()

    @Before fun setup() {
        org.kaijinlab.hush.initWorkManagerWithoutRealWork(context)
        DeferredActionWork.cancelTimers(context)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        DeferredActionWork.recordState(context, true)
    }
    @After fun cleanup() { DeferredActionWork.cancelTimers(context) }

    @Test fun `one minute uses exact elapsed realtime wakeup allowed in idle`() {
        val before = SystemClock.elapsedRealtime()
        DeferredActionWork.enqueue(context, setOf(PrivacyFeature.BATTERY_SAVER), true, 60_000, DeferredActionWork.generation(context))
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.getType())
        assertEquals(before + 60_000, alarm.triggerAtMs)
        assertEquals(0L, alarm.windowLengthMs)
        assertTrue(alarm.isAllowWhileIdle)
        assertEquals(before + 60_000, DeferredActionWork.nextDeadline(context)!!.second)
    }

    @Test fun `screen-off arms the alarm before the initial worker runs`() {
        val settings = PreferenceManager.getInstance(context)
        val oldEnabled = settings.isGlobalPrivacyEnabled
        val oldDelay = settings.lockDelaySeconds
        val flags = PrivacyFeature.values().associateWith { settings.getFeatureDisableOnLock(it) }
        try {
            settings.isGlobalPrivacyEnabled = true
            settings.lockDelaySeconds = 60
            PrivacyFeature.values().forEach { settings.setFeatureDisableOnLock(it, it == PrivacyFeature.BATTERY_SAVER) }
            org.kaijinlab.hush.initWorkManagerWithoutRealWork(context, onRun = {
                assertEquals(1, alarms.scheduledAlarms.size)
            })
            PrivacyActionWork.enqueue(context, true, false, "screen_state", "Screen Off")
            assertEquals(1, alarms.scheduledAlarms.size)
        } finally {
            settings.isGlobalPrivacyEnabled = oldEnabled
            settings.lockDelaySeconds = oldDelay
            flags.forEach { (feature, enabled) -> settings.setFeatureDisableOnLock(feature, enabled) }
        }
    }

    @Test fun `duplicate signals do not restart the deadline`() {
        val generation = DeferredActionWork.generation(context)
        DeferredActionWork.enqueue(context, setOf(PrivacyFeature.WIFI), true, 60_000, generation)
        val deadline = alarms.scheduledAlarms.single().triggerAtMs
        DeferredActionWork.enqueue(context, setOf(PrivacyFeature.WIFI), true, 120_000, generation)
        assertEquals(deadline, alarms.scheduledAlarms.single().triggerAtMs)
    }

    @Test fun `waking screen without unlocking preserves timer but unlock cancels it`() {
        val generation = DeferredActionWork.generation(context)
        DeferredActionWork.enqueue(context, setOf(PrivacyFeature.WIFI), true, 60_000, generation)
        DeferredActionWork.recordState(context, true)
        assertEquals(1, alarms.scheduledAlarms.size)
        DeferredActionWork.recordState(context, false)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertNull(DeferredActionWork.nextDeadline(context))
    }

    @Test fun `generation changed during scheduling refuses obsolete alarm`() {
        val old = DeferredActionWork.generation(context)
        DeferredActionWork.recordState(context, false)
        DeferredActionWork.enqueue(context, setOf(PrivacyFeature.WIFI), true, 60_000, old)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun `alarm dispatch preserves underscore enum names and never replays after unlock`() {
        var names: String? = null
        org.kaijinlab.hush.initWorkManagerWithoutRealWork(context, onRun = {
            names = it.inputData.getString(DeferredActionWork.KEY_FEATURES)
        })
        val generation = DeferredActionWork.generation(context)
        val selected = setOf(PrivacyFeature.BATTERY_SAVER, PrivacyFeature.MOBILE_DATA)
        DeferredActionWork.enqueue(context, selected, true, 60_000, generation)
        val name = "${DeferredActionWork.tag(true)}_${generation}_BATTERY_SAVER,MOBILE_DATA"
        DeferredActionWork.dispatchAlarm(context, name)
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name + "_due").get()
        assertEquals(1, work.size)
        assertEquals(selected.map { it.name }.toSet(), names!!.split(',').toSet())
        DeferredActionWork.dispatchAlarm(context, name)
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork(name + "_due").get().size)
        DeferredActionWork.recordState(context, false)
        DeferredActionWork.dispatchAlarm(context, name)
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork(name + "_due").get().size)
    }

    @Test
    @Config(sdk = [30])
    fun `Android 11 dispatch does not require an expedited foreground notification`() {
        val generation = DeferredActionWork.generation(context)
        DeferredActionWork.enqueue(context, setOf(PrivacyFeature.BATTERY_SAVER), true, 60_000, generation)
        val name = "${DeferredActionWork.tag(true)}_${generation}_BATTERY_SAVER"
        DeferredActionWork.dispatchAlarm(context, name)
        val manager = WorkManager.getInstance(context)
        val id = manager.getWorkInfosForUniqueWork(name + "_due").get().single().id
        var state = manager.getWorkInfoById(id).get()!!.state
        for (attempt in 0 until 100) {
            if (state.isFinished) break
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
            state = manager.getWorkInfoById(id).get()!!.state
        }
        assertEquals(androidx.work.WorkInfo.State.SUCCEEDED, state)
    }

    @Test fun `without exact alarm permission keeps durable best effort fallback`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(DeferredActionWork.canScheduleExactAlarms(context))
        DeferredActionWork.enqueue(context, setOf(PrivacyFeature.WIFI), true, 60_000, DeferredActionWork.generation(context))
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosByTag(DeferredActionWork.tag(true)).get().size)
    }
}
