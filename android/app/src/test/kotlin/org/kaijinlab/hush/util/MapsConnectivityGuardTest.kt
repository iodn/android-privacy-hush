package org.kaijinlab.hush.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.kaijinlab.hush.data.FeatureState
import org.kaijinlab.hush.data.PrivacyFeature

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MapsConnectivityGuardTest {
    private lateinit var context: Context
    private lateinit var settings: PreferenceManager
    private lateinit var guard: Guard
    private var saved: Map<String, *> = emptyMap<String, Any>()
    private val location = PrivacyFeature.LOCATION
    private val wifi = PrivacyFeature.WIFI
    private val data = PrivacyFeature.MOBILE_DATA
    private class Guard(context: Context) : MapsConnectivityGuard(context) {
        var clock = 100_000L
        var isLocked = true
        var usage = MapsUsage(false, true)
        val states = mutableMapOf<PrivacyFeature, FeatureState>()
        val restored = mutableListOf<PrivacyFeature>()
        val queued = mutableListOf<Set<PrivacyFeature>>()
        override fun now() = clock
        override fun locked() = isLocked
        override suspend fun observe() = usage
        override suspend fun prepare() = true
        override suspend fun state(feature: PrivacyFeature) = states[feature] ?: FeatureState.ENABLED
        override suspend fun restore(feature: PrivacyFeature): Boolean { restored += feature; states[feature] = FeatureState.ENABLED; return true }
        override fun queueDisable(features: Set<PrivacyFeature>, generation: Long) { queued += features }
    }
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        settings = PreferenceManager.getInstance(context)
        saved = settings.getRawPreferences().all.toMap()
        settings.getRawPreferences().edit().clear().putLong(DeferredActionWork.KEY_GENERATION, 50).commit()
        settings.isGlobalPrivacyEnabled = true
        for (f in MapsConnectivityGuard.FEATURES) { settings.setFeatureKeepForMaps(f, true); settings.setFeatureDisableOnLock(f, true) }
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(true)
        guard = Guard(context)
    }
    @After fun cleanup() {
        val editor = settings.getRawPreferences().edit().clear()
        for ((k,v) in saved) when(v) {
            is String -> editor.putString(k,v)
            is Boolean -> editor.putBoolean(k,v)
            is Int -> editor.putInt(k,v)
            is Long -> editor.putLong(k,v)
            is Float -> editor.putFloat(k,v)
            is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(k,v as Set<String>)
        }
        editor.commit()
    }
    @Test fun `viewing Maps or navigating keeps only opted-in controls on`() = runBlocking {
        guard.usage = MapsUsage(true,true)
        assertTrue(guard.shouldKeep(location)); assertTrue(guard.shouldKeep(wifi)); assertTrue(guard.shouldKeep(data))
        assertFalse(guard.shouldKeep(PrivacyFeature.CAMERA))
        settings.setFeatureKeepForMaps(data,false)
        assertFalse(guard.shouldKeep(data))
    }
    @Test fun `cached inactive Maps does not exempt any control`() = runBlocking {
        assertFalse(guard.shouldKeep(location)); assertFalse(guard.shouldKeep(wifi))
    }
    @Test fun `brief signal or service gaps do not interrupt retained navigation`() = runBlocking {
        guard.usage = MapsUsage(true,true); assertTrue(guard.shouldKeep(location))
        guard.usage = MapsUsage(false,true);guard.clock += MapsConnectivityGuard.GRACE_MS-1
        assertTrue(guard.shouldKeep(location))
        guard.tick();assertTrue(guard.queued.isEmpty())
        guard.clock += 2;guard.tick()
        assertEquals(listOf(setOf(location)),guard.queued)
        guard.tick();assertEquals("retry cleanup until the queued action actually switches it off",2,guard.queued.size)
        guard.states[location]=FeatureState.DISABLED;guard.tick()
        guard.states[location]=FeatureState.ENABLED;guard.tick()
        assertEquals("after observing off, do not fight a later manual on",2,guard.queued.size)
    }
    @Test fun `watching Maps before a long feature timer is due never brings that timer forward`() = runBlocking {
        guard.usage = MapsUsage(true,true);guard.tick()
        guard.usage = MapsUsage(false,true);guard.clock += MapsConnectivityGuard.GRACE_MS+1;guard.tick()
        assertTrue(guard.queued.isEmpty())
    }
    @Test fun `other controls are not disabled again merely because a user turned them on manually`() = runBlocking {
        assertFalse(guard.shouldKeep(location))
        guard.tick();assertTrue(guard.queued.isEmpty())
    }
    @Test fun `new lock generations cannot reuse an older Maps session`() = runBlocking {
        guard.usage = MapsUsage(true,true);assertTrue(guard.shouldKeep(location))
        settings.getRawPreferences().edit().putLong(DeferredActionWork.KEY_GENERATION,51).commit()
        guard.usage = MapsUsage(false,true)
        assertFalse(guard.shouldKeep(location));guard.tick();assertTrue(guard.queued.isEmpty())
    }
    @Test fun `unverified detection preserves access rather than cutting a possible route`() = runBlocking {
        guard.usage = MapsUsage(false,false)
        assertTrue(guard.shouldKeep(location))
        assertTrue(settings.getRawPreferences().getBoolean(MapsConnectivityGuard.WARNING_KEY,false))
        guard.usage = MapsUsage(false,true);assertFalse(guard.shouldKeep(location))
    }
    @Test fun `unlocking or pausing protection prevents delayed cleanup`() = runBlocking {
        guard.usage=MapsUsage(true,true);guard.shouldKeep(location)
        guard.usage=MapsUsage(false,true);guard.clock+=MapsConnectivityGuard.GRACE_MS+1
        guard.isLocked=false;guard.tick();assertTrue(guard.queued.isEmpty())
        guard.isLocked=true;settings.isGlobalPrivacyEnabled=false;guard.tick();assertTrue(guard.queued.isEmpty())
    }
    @Test fun `Maps starting in background restores only controls Hush previously switched off`() = runBlocking {
        MapsConnectivityGuard.recordDisabled(context, location, 50)
        MapsConnectivityGuard.recordDisabled(context, wifi, 50)
        guard.states[location]=FeatureState.DISABLED;guard.states[wifi]=FeatureState.DISABLED;guard.states[data]=FeatureState.DISABLED
        guard.usage=MapsUsage(true,true);guard.tick()
        assertEquals(setOf(location,wifi),guard.restored.toSet())
        assertFalse("manually disabled mobile data remains off",data in guard.restored)
        guard.states[location]=FeatureState.DISABLED;guard.tick()
        assertEquals("do not fight a later manual off",1,guard.restored.count { it==location })
    }
    @Test fun `restored connections are disabled again after Maps stops`() = runBlocking {
        MapsConnectivityGuard.recordDisabled(context,location,50)
        guard.states[location]=FeatureState.DISABLED;guard.usage=MapsUsage(true,true);guard.tick()
        guard.usage=MapsUsage(false,true);guard.clock+=MapsConnectivityGuard.GRACE_MS+1;guard.tick()
        assertEquals(listOf(setOf(location)),guard.queued)
    }
    @Test fun `durable ownership survives recreating the guard after process death`() = runBlocking {
        MapsConnectivityGuard.recordDisabled(context,location,50)
        guard=Guard(context).apply { states[location]=FeatureState.DISABLED;usage=MapsUsage(true,true) }
        guard.tick();assertEquals(listOf(location),guard.restored)
    }

}
