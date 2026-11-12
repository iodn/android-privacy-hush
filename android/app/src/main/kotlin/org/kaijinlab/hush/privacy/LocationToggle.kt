package org.kaijinlab.hush.privacy

import android.os.Build
import org.kaijinlab.hush.data.*
import org.kaijinlab.hush.root.RootManager
import org.kaijinlab.hush.util.StatusParsingUtils

open class LocationToggle(rootManager: RootManager) : BasePrivacyToggle(rootManager) {

    override val feature = PrivacyFeature.LOCATION
    override val featureName = "Location Services"

    override val enableCommands = getEnableCommandsForApi()
    override val disableCommands = getDisableCommandsForApi()
    override val statusCommands = getStatusCommandsForApi()
    
    // Before Android 10 the location service obeys only location_providers_allowed, which takes only
    // +provider / -provider: location_mode, '' and a plain list all exit 0 and change nothing.
    private fun getEnableCommandsForApi(): List<CommandSet> =
        if (Build.VERSION.SDK_INT >= 29) {
            listOf(
                CommandSet("settings put secure location_mode 3"),
                CommandSet("settings put secure location_providers_allowed +gps,+network")
            )
        } else {
            listOf(
                CommandSet("settings put secure location_providers_allowed +gps && settings put secure location_providers_allowed +network")
            )
        }

    private fun getDisableCommandsForApi(): List<CommandSet> =
        if (Build.VERSION.SDK_INT >= 29) {
            listOf(
                CommandSet("settings put secure location_mode 0"),
                CommandSet("settings put secure location_providers_allowed ''")
            )
        } else {
            listOf(
                CommandSet("settings put secure location_providers_allowed -gps && settings put secure location_providers_allowed -network")
            )
        }

    private fun getStatusCommandsForApi(): List<CommandSet> =
        if (Build.VERSION.SDK_INT >= 29) {
            listOf(
                CommandSet("settings get secure location_mode"),
                CommandSet("settings get secure location_providers_allowed"),
                CommandSet("dumpsys location | grep 'Location'")
            )
        } else {
            listOf(
                CommandSet("settings get secure location_providers_allowed")
            )
        }

    override fun parseStatusOutput(output: String): FeatureState {
        return StatusParsingUtils.parseLocationOutput(output)
    }
}
