package org.kaijinlab.hush.privilege

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.kaijinlab.hush.data.FeatureState
import org.kaijinlab.hush.data.PrivacyFeature
import org.kaijinlab.hush.util.PreferenceManager
import org.kaijinlab.hush.worker.PrivacyActionWorker

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RootSensorPolicyTest {
    private lateinit var context: Application
    private val profiles = setOf("0:0", "10:10")
    private val base = mutableSetOf<String>()
    private val external = mutableSetOf<String>()
    private val privacy = mutableSetOf<String>()
    private val commands = mutableListOf<String>()
    private var failBlock = false
    private var failHandoff = false
    private val camera = PrivacyFeature.CAMERA
    private val mic = PrivacyFeature.MICROPHONE
    private val prefs get() = PreferenceManager.getInstance(context).getRawPreferences()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.applicationInfo.sourceDir = "/test/hush.apk"
        clearOwned()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        PrivacyActionWorker.lastLockAtMillis = 0
        PrivacyActionWorker.lastUnlockAtMillis = 0
    }
    @After fun cleanup() {
        clearOwned()
        PrivacyActionWorker.lastLockAtMillis = 0
        PrivacyActionWorker.lastUnlockAtMillis = 0
    }
    private fun clearOwned() {
        for (f in listOf(camera, mic)) prefs.edit().remove(RootSensorPolicy.ownedKey(f)).commit()
    }
    private fun owners(feature: PrivacyFeature) = prefs.getStringSet(RootSensorPolicy.ownedKey(feature), emptySet()).orEmpty()
    private suspend fun run(command: String): CommandResult {
        commands += command
        val words = command.split(' ')
        if (command.startsWith("cmd sensor_privacy disable")) {
            privacy.remove(words.last())
            return CommandResult.success()
        }
        val offset = words.indexOf("org.kaijinlab.hush.root.RootSensorCommand") + 1
        val action = words[offset]
        val name = words[offset + 1]
        val ids = words.getOrNull(offset + 2)?.split(',')?.toSet().orEmpty()
        fun result(ids: Set<String>) = CommandResult.success(listOf(if (ids.isEmpty()) "NONE" else ids.sorted().joinToString(",")))
        return when (action) {
            "targets" -> result(profiles.filter { "$name@$it" !in base && "$name@$it" !in external }.toSet())
            "base" -> result(ids.filter { "$name@$it" in base }.toSet())
            "read" -> CommandResult.success(listOf(if (name in privacy || profiles.all { "$name@$it" in base || "$name@$it" in external }) "BLOCKED" else "ALLOWED"))
            "block" -> {
                val f = if (name == "camera") camera else mic
                assertTrue("recovery state must reach disk before changing policies", owners(f).containsAll(ids))
                if (failBlock) CommandResult.failure("process interrupted") else { base += ids.map { "$name@$it" }; CommandResult.success() }
            }
            "allow" -> { base -= ids.map { "$name@$it" }.toSet(); CommandResult.success() }
            "handoff" -> if (failHandoff) CommandResult.failure("privacy change refused") else {
                privacy += name
                base -= ids.map { "$name@$it" }.toSet()
                CommandResult.success()
            }
            else -> error(command)
        }
    }

    @Test fun `camera and microphone are independently blocked and restored`() = runBlocking {
        RootSensorPolicy.apply(context, camera, false, ::run)
        assertEquals(FeatureState.DISABLED, RootSensorPolicy.read(context, camera, ::run))
        assertEquals(FeatureState.ENABLED, RootSensorPolicy.read(context, mic, ::run))
        RootSensorPolicy.apply(context, mic, false, ::run)
        RootSensorPolicy.apply(context, camera, true, ::run)
        assertEquals(FeatureState.ENABLED, RootSensorPolicy.read(context, camera, ::run))
        assertEquals(FeatureState.DISABLED, RootSensorPolicy.read(context, mic, ::run))
    }
    @Test fun `existing work profile restrictions are never claimed or cleared`() = runBlocking {
        external += "camera@10:10"
        RootSensorPolicy.apply(context, camera, false, ::run)
        assertEquals(setOf("0:0"), owners(camera))
        RootSensorPolicy.apply(context, camera, true, ::run)
        assertTrue("camera@10:10" in external)
        assertTrue(commands.filter { " allow " in it }.all { it.endsWith("camera 0:0") })
        assertEquals(FeatureState.ENABLED, RootSensorPolicy.read(context, camera, ::run))
    }
    @Test fun `blocking covers both personal and work profiles`() = runBlocking {
        RootSensorPolicy.apply(context, camera, false, ::run)
        assertEquals(profiles, owners(camera))
        assertTrue(profiles.all { "camera@$it" in base })
        RootSensorPolicy.handoffAfterUnlock(context, ::run)
        assertTrue(base.isEmpty())
        assertTrue("camera" in privacy)
    }
    @Test fun `unlock handoff returns manual control while keeping sensor blocked for its timer`() = runBlocking {
        RootSensorPolicy.apply(context, camera, false, ::run)
        RootSensorPolicy.handoffAfterUnlock(context, ::run)
        assertTrue(base.none { it.startsWith("camera@") })
        assertTrue("camera" in privacy)
        assertFalse(owners(camera).isNotEmpty())
        assertEquals(FeatureState.DISABLED, RootSensorPolicy.read(context, camera, ::run))
        RootSensorPolicy.apply(context, camera, true, ::run)
        assertEquals(FeatureState.ENABLED, RootSensorPolicy.read(context, camera, ::run))
    }
    @Test fun `failed handoff keeps block and durable ownership for another recovery attempt`() = runBlocking {
        RootSensorPolicy.apply(context, camera, false, ::run)
        failHandoff = true
        RootSensorPolicy.handoffAfterUnlock(context, ::run)
        assertTrue(profiles.all { "camera@$it" in base })
        assertTrue(owners(camera).isNotEmpty())
    }
    @Test fun `recovery does not remove blocks during lock or while a new lock is starting`() = runBlocking {
        RootSensorPolicy.apply(context, camera, false, ::run)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        RootSensorPolicy.handoffAfterUnlock(context, ::run)
        assertTrue(profiles.all { "camera@$it" in base })
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        PrivacyActionWorker.lastLockAtMillis = 10
        RootSensorPolicy.handoffAfterUnlock(context, ::run)
        assertTrue(profiles.all { "camera@$it" in base })
        PrivacyActionWorker.lastUnlockAtMillis = 11
        RootSensorPolicy.handoffAfterUnlock(context, ::run)
        assertTrue(base.none { it.startsWith("camera@") })
    }
    @Test fun `an interrupted policy command still leaves recovery ownership`() = runBlocking {
        failBlock = true
        assertFalse(RootSensorPolicy.apply(context, camera, false, ::run)!!.success)
        assertTrue(owners(camera).isNotEmpty())
        failBlock = false
        base += profiles.map { "camera@$it" } // A partially completed command may outlive its caller.
        RootSensorPolicy.handoffAfterUnlock(context, ::run)
        assertTrue(base.none { it.startsWith("camera@") })
    }
    @Test fun `a relock prevents restoration even if an unlock worker had started`() = runBlocking {
        RootSensorPolicy.apply(context, camera, false, ::run)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)
        assertFalse(RootSensorPolicy.apply(context, camera, true, ::run)!!.success)
        assertTrue(profiles.all { "camera@$it" in base })
    }
    @Test fun `a failed state query is unknown rather than an invented allowed state`() = runBlocking {
        assertEquals(FeatureState.UNKNOWN, RootSensorPolicy.read(context, camera) { CommandResult.failure("binder unavailable") })
    }
}
