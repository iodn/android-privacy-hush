package org.kaijinlab.hush.privacy

import org.kaijinlab.hush.data.CommandSet
import org.kaijinlab.hush.data.FeatureState
import org.kaijinlab.hush.root.RootManager
import org.kaijinlab.hush.util.StatusParsingUtils

/**
 * Camera and microphone are the same switch with a different sensor name: one
 * `cmd sensor_privacy` command shape, one `dumpsys sensor_privacy` read, one parser.
 * They were written out twice, so a change to that shared shape - a new Android version
 * wanting different arguments - had to be made in both files or they would silently drift.
 *
 * Available only from Android 12; see DeviceDetector.supportsSensorPrivacyToggle(), which is
 * what actually keeps these two off the list on older versions.
 *
 * @param sensorName as `cmd sensor_privacy` names it ("camera", "microphone")
 * @param sensorId as `dumpsys sensor_privacy` reports it (2 camera, 1 microphone)
 */
abstract class SensorPrivacyToggle(
    rootManager: RootManager,
    sensorName: String,
    private val sensorId: Int
) : BasePrivacyToggle(rootManager) {

    override val requireStateConfirmation: Boolean get() = true

    // Enable = allow access (privacy off), disable = block access (privacy on).
    // user_id 0 is the primary user. Android refuses this change once the device is locked.
    override val enableCommands = listOf(
        CommandSet("cmd sensor_privacy disable 0 $sensorName")
    )

    override val disableCommands = listOf(
        CommandSet("cmd sensor_privacy enable 0 $sensorName")
    )

    override val statusCommands = listOf(
        CommandSet("dumpsys sensor_privacy")
    )

    override fun parseStatusOutput(output: String): FeatureState =
        StatusParsingUtils.parseSensorPrivacyOutput(output, sensorId)
}
