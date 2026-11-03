package org.kaijinlab.hush

import android.app.Activity
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.kaijinlab.hush.util.*
import org.kaijinlab.hush.data.PrivacyFeature

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HushSettingsTest {
    private lateinit var context: Context
    private lateinit var controller: HushController

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getInstance(context).getRawPreferences().edit().clear().commit()
        controller = HushController(Robolectric.buildActivity(Activity::class.java).setup().get())
    }

    @After fun clearOverrides() {
        val prefs = PreferenceManager.getInstance(context)
        PrivacyFeature.values().forEach {
            prefs.setFeatureDelayOverride(it, true, null)
            prefs.setFeatureDelayOverride(it, false, null)
        }
    }

    @Test fun `fresh install requires explicit protection activation`() {
        assertFalse(PreferenceManager.getInstance(context).isGlobalPrivacyEnabled)
    }

    @Test fun `theme preference survives controller recreation`() = runBlocking {
        controller.set(mapOf("key" to "theme", "value" to "oled"))
        val recreated = HushController(Robolectric.buildActivity(Activity::class.java).setup().get())
        assertEquals("oled", recreated.handle("appearance", null))
    }

    @Test fun `invalid delay does not overwrite saved configuration`() = runBlocking {
        controller.set(mapOf("key" to "lockDelay", "value" to 120))
        try {
            controller.set(mapOf("key" to "lockDelay", "value" to 1801))
            fail("Invalid delay accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(120, PreferenceManager.getInstance(context).lockDelaySeconds)
    }

    @Test fun `feature preferences reach the engine storage`() = runBlocking {
        controller.set(mapOf("key" to "lock", "feature" to "BATTERY_SAVER", "value" to true))
        assertTrue(PreferenceManager.getInstance(context).getFeatureDisableOnLock(PrivacyFeature.BATTERY_SAVER))
    }

    @Test fun `accessibility status recognizes full and abbreviated component names`() {
        val name = "${context.packageName}/${context.packageName}.accessibility.PrivacyAccessibilityService"
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, name)
        assertTrue(AccessibilityServiceManager.isAccessibilityServiceEnabled(context))
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "${context.packageName}/.accessibility.PrivacyAccessibilityService")
        assertTrue(AccessibilityServiceManager.isAccessibilityServiceEnabled(context))
    }
    @Test fun `feature delays are independent and can return to the default`() = runBlocking {
        val prefs = PreferenceManager.getInstance(context)
        prefs.lockDelaySeconds = 10
        controller.set(mapOf("key" to "lockDelay", "feature" to "BATTERY_SAVER", "value" to 30))
        controller.set(mapOf("key" to "unlockDelay", "feature" to "BATTERY_SAVER", "value" to 5))
        assertEquals(30, prefs.getFeatureDelaySeconds(PrivacyFeature.BATTERY_SAVER, true))
        assertEquals(5, prefs.getFeatureDelaySeconds(PrivacyFeature.BATTERY_SAVER, false))
        assertEquals(10, prefs.getFeatureDelaySeconds(PrivacyFeature.WIFI, true))
        controller.set(mapOf("key" to "lockDelay", "feature" to "BATTERY_SAVER", "value" to -1))
        assertNull(prefs.getFeatureDelayOverride(PrivacyFeature.BATTERY_SAVER, true))
        assertEquals(10, prefs.getFeatureDelaySeconds(PrivacyFeature.BATTERY_SAVER, true))
    }

    @Test fun `invalid feature delays leave the saved value intact`() = runBlocking {
        val prefs = PreferenceManager.getInstance(context)
        prefs.setFeatureDelayOverride(PrivacyFeature.BATTERY_SAVER, true, 5)
        try {
            controller.set(mapOf("key" to "lockDelay", "feature" to "BATTERY_SAVER", "value" to 1801))
            fail("Invalid delay accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(5, prefs.getFeatureDelaySeconds(PrivacyFeature.BATTERY_SAVER, true))
    }

    @Test fun `sensors default to immediate while supporting explicit delays`() {
        val prefs = PreferenceManager.getInstance(context)
        prefs.lockDelaySeconds = 30
        assertEquals(0, prefs.getFeatureDelaySeconds(PrivacyFeature.CAMERA, true))
        prefs.setFeatureDelayOverride(PrivacyFeature.CAMERA, true, 5)
        assertEquals(5, prefs.getFeatureDelaySeconds(PrivacyFeature.CAMERA, true))
    }

    @Test fun `global and feature timers accept thirty minutes`() = runBlocking {
        val prefs = PreferenceManager.getInstance(context)
        for (key in listOf("lockDelay", "unlockDelay")) {
            controller.set(mapOf("key" to key, "value" to 1800))
            controller.set(mapOf("key" to key, "feature" to "BATTERY_SAVER", "value" to 1799))
        }
        assertEquals(1800, prefs.lockDelaySeconds)
        assertEquals(1800, prefs.unlockDelaySeconds)
        assertEquals(1799, prefs.getFeatureDelaySeconds(PrivacyFeature.BATTERY_SAVER, true))
        assertEquals(1799, prefs.getFeatureDelaySeconds(PrivacyFeature.BATTERY_SAVER, false))
    }

}
