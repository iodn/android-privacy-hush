package org.kaijinlab.hush.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.kaijinlab.hush.data.PrivacyFeature

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeferredActionWorkTest {
    @Test fun `durable work carries full thirty minute deadline and live state contract`() {
        val request = DeferredActionWork.buildRequest(setOf(PrivacyFeature.BATTERY_SAVER), true, 1800000L, 42L)
        assertEquals(1800000L, request.workSpec.initialDelay)
        assertEquals("BATTERY_SAVER", request.workSpec.input.getString(DeferredActionWork.KEY_FEATURES))
        assertEquals(42L, request.workSpec.input.getLong(DeferredActionWork.KEY_GENERATION, -1L))
        assertTrue(request.workSpec.input.getBoolean(PrivacyActionWork.KEY_IS_LOCKING, false))
        assertFalse(request.workSpec.input.getBoolean(PrivacyActionWork.KEY_IS_DEVICE_LOCKED, true))
        assertTrue(request.tags.contains(DeferredActionWork.tag(true)))
        assertNotEquals(DeferredActionWork.tag(true), DeferredActionWork.tag(false))
    }

    @Test fun `duplicate signals preserve deadline but opposite transitions invalidate old work`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferenceManager.getInstance(context).getRawPreferences()
        prefs.edit().remove("last_observed_lock").remove(DeferredActionWork.KEY_GENERATION).commit()
        try {
            DeferredActionWork.recordState(context, true)
            val first = DeferredActionWork.generation(context)
            DeferredActionWork.recordState(context, true)
            assertEquals(first, DeferredActionWork.generation(context))
            DeferredActionWork.recordState(context, false)
            assertTrue(first != DeferredActionWork.generation(context))
            assertNull(DeferredActionWork.generationForState(context, true))
            val unlocked = DeferredActionWork.generation(context)
            DeferredActionWork.recordState(context, true)
            assertTrue(unlocked != DeferredActionWork.generation(context))
            assertTrue(first != DeferredActionWork.generation(context))
        } finally {
            prefs.edit().remove("last_observed_lock").remove(DeferredActionWork.KEY_GENERATION).commit()
        }
    }
    @Test fun `duplicate recovery preserves queued job and unlock cancels persistent lock timers`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        org.kaijinlab.hush.initWorkManagerWithoutRealWork(context)
        val manager = androidx.work.WorkManager.getInstance(context)
        DeferredActionWork.recordState(context, true)
        val generation = DeferredActionWork.generation(context)
        DeferredActionWork.enqueue(context, setOf(PrivacyFeature.WIFI), true, 1800000L, generation)
        val first = manager.getWorkInfosByTag(DeferredActionWork.tag(true)).get().single()
        DeferredActionWork.enqueue(context, setOf(PrivacyFeature.WIFI), true, 1800000L, generation)
        val recovered = manager.getWorkInfosByTag(DeferredActionWork.tag(true)).get().single()
        assertEquals(first.id, recovered.id)
        assertEquals(androidx.work.WorkInfo.State.ENQUEUED, recovered.state)
        PendingLockWork.recordUnlock(context)
        PendingLockWork.cancel(context, "test", Constants.Work.NAME_LOCK)
        assertEquals(androidx.work.WorkInfo.State.CANCELLED, manager.getWorkInfoById(first.id).get()!!.state)
    }

}
