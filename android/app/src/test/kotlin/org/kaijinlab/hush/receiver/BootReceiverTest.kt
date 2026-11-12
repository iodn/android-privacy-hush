package org.kaijinlab.hush.receiver

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import org.kaijinlab.hush.initWorkManagerWithoutRealWork
import org.kaijinlab.hush.service.PrivacyMonitorService
import org.kaijinlab.hush.util.DeferredActionWork
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BootReceiverTest {
    private lateinit var context: Application
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        initWorkManagerWithoutRealWork(context)
    }

    @Test fun `boot starts foreground monitor and health recovery without opening activity`() {
        val oldGeneration = DeferredActionWork.generation(context)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(301))
        assertEquals(PrivacyMonitorService::class.java.name, shadowOf(context).nextStartedService.component!!.className)
        assertEquals(oldGeneration + 1, DeferredActionWork.generation(context))
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork("hush_service_health").get().size)
    }

    @Test fun `app update resumes monitor without invalidating current lock cycle`() {
        val oldGeneration = DeferredActionWork.generation(context)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(301))
        assertEquals(PrivacyMonitorService::class.java.name, shadowOf(context).nextStartedService.component!!.className)
        assertEquals(oldGeneration, DeferredActionWork.generation(context))
    }
}
