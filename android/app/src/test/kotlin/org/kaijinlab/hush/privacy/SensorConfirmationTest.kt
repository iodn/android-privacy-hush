package org.kaijinlab.hush.privacy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.kaijinlab.hush.data.FeatureState
import org.kaijinlab.hush.data.PrivacyFeature
import org.kaijinlab.hush.privilege.CommandResult
import org.kaijinlab.hush.root.RootManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SensorConfirmationTest {
    private class Sensor(private val state: FeatureState) : SensorPrivacyToggle(RootManager.getInstance(Unit), "camera", 2) {
        override val feature = PrivacyFeature.CAMERA
        override val featureName = "Camera"
        override val readBackAttempts = 1
        override suspend fun runFeatureAction(enable: Boolean) = CommandResult.success()
        override suspend fun readFeatureState() = state
        override suspend fun runCommands(commands: List<String>) = CommandResult.success(emptyList())
    }
    @Test fun `accepted sensor command with unreadable state is never reported successful`() = runBlocking {
        val result = Sensor(FeatureState.UNKNOWN).disable()
        assertFalse(result.success)
        assertTrue(result.message!!.contains("Could not verify"))
    }
    @Test fun `a verified sensor block is successful`() = runBlocking {
        assertTrue(Sensor(FeatureState.DISABLED).disable().success)
    }
}
