package org.kaijinlab.hush.privacy

import org.kaijinlab.hush.data.*
import org.kaijinlab.hush.root.RootManager

open class BatterySaverToggle(rootManager: RootManager) : BasePrivacyToggle(rootManager) {

    companion object {
        // Compiled once: parseStatusOutput runs on every read-back attempt.
        private val ENABLED_FLAG = Regex("enabled=(true|false)")
    }

    override val feature = PrivacyFeature.BATTERY_SAVER
    override val requireStateConfirmation: Boolean get() = true
    override val featureName = "Battery Saver"

    override val enableCommands = commandsFor(1)
    override val disableCommands = commandsFor(0)

    // The power command first: on a charger Android refuses it without touching low_power, while a
    // written low_power=1 stays armed.
    private fun commandsFor(mode: Int): List<CommandSet> = listOf(
        CommandSet("cmd power set-mode $mode"),
        CommandSet("settings put global low_power $mode")
    )

    // The power service's own state: "Enabled=" under the state machine from Android 9, "mLowPowerModeEnabled=" on 8.
    // The key last, for a backend that may not dump services: an unreadable enable is never claimed, so never undone.
    override val statusCommands = listOf(
        CommandSet("dumpsys power | grep -A3 'Battery saver state machine:' | grep -m1 'Enabled='"),
        CommandSet("dumpsys power | grep -m1 'mLowPowerModeEnabled='"),
        CommandSet("settings get global low_power")
    )

    // The power service refuses either direction while powered, and its command still exits 0.
    override suspend fun whyUnchanged(): String? {
        val powered = runCommands(listOf("dumpsys power | grep -m1 'mIsPowered='"))
        return if (powered.success && powered.output.any { "mIsPowered=true" in it }) {
            "Android does not switch Battery Saver while the phone is charging"
        } else null
    }

    override fun parseStatusOutput(output: String): FeatureState {
        ENABLED_FLAG.find(output.lowercase())?.let {
            return if (it.groupValues[1] == "true") FeatureState.ENABLED else FeatureState.DISABLED
        }
        return when (output.trim()) {
            "1" -> FeatureState.ENABLED
            "0" -> FeatureState.DISABLED
            else -> FeatureState.UNKNOWN
        }
    }
}
