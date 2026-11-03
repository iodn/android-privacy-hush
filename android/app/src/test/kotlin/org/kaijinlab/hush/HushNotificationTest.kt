package org.kaijinlab.hush

import android.app.Application
import android.app.NotificationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.kaijinlab.hush.service.PrivacyMonitorService
import org.kaijinlab.hush.util.Constants
import org.kaijinlab.hush.util.PreferenceManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HushNotificationTest {
    @Test fun `notification follows protection changes from any entry point`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        initWorkManagerWithoutRealWork(context)
        val prefs = PreferenceManager.getInstance(context)
        val saved = prefs.isGlobalPrivacyEnabled
        prefs.isGlobalPrivacyEnabled = false
        val serviceController = Robolectric.buildService(PrivacyMonitorService::class.java).create()
        try {
            val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
            assertEquals("Protection paused", manager.getNotification(Constants.ServiceNotification.NOTIFICATION_ID).extras.getCharSequence("android.text"))
            prefs.isGlobalPrivacyEnabled = true
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Protection active", manager.getNotification(Constants.ServiceNotification.NOTIFICATION_ID).extras.getCharSequence("android.text"))
            prefs.isGlobalPrivacyEnabled = false
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Protection paused", manager.getNotification(Constants.ServiceNotification.NOTIFICATION_ID).extras.getCharSequence("android.text"))
        } finally {
            serviceController.destroy()
            prefs.isGlobalPrivacyEnabled = saved
        }
    }
    @Test fun `a cancelled earlier countdown cannot clear a newer countdown`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        initWorkManagerWithoutRealWork(context)
        val prefs = PreferenceManager.getInstance(context)
        val saved = prefs.isGlobalPrivacyEnabled
        prefs.isGlobalPrivacyEnabled = false
        val controller = Robolectric.buildService(PrivacyMonitorService::class.java).create()
        try {
            val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
            val first = java.util.UUID.randomUUID()
            val next = java.util.UUID.randomUUID()
            PrivacyMonitorService.showCountdown(first, "Lock actions in 5s")
            PrivacyMonitorService.showCountdown(next, "Unlock actions in 2s")
            PrivacyMonitorService.showCountdown(first, null)
            assertEquals("Unlock actions in 2s", manager.getNotification(Constants.ServiceNotification.NOTIFICATION_ID).extras.getCharSequence("android.text"))
            PrivacyMonitorService.showCountdown(next, null)
            assertEquals("Protection paused", manager.getNotification(Constants.ServiceNotification.NOTIFICATION_ID).extras.getCharSequence("android.text"))
        } finally { controller.destroy(); prefs.isGlobalPrivacyEnabled = saved }
    }

}
