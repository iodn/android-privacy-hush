package org.kaijinlab.hush.privacy

import android.os.Build
import org.kaijinlab.hush.data.*
import org.kaijinlab.hush.root.RootManager
import org.kaijinlab.hush.util.StatusParsingUtils

open class AirplaneModeToggle(rootManager: RootManager) : BasePrivacyToggle(rootManager) {

    override val feature = PrivacyFeature.AIRPLANE_MODE
    override val featureName = "Airplane Mode"

    override val enableCommands = commandsFor(true)
    override val disableCommands = commandsFor(false)

    // The key alone switches only Bluetooth, and only root may send the broadcast, so the fallback is
    // root-gated before it writes anything. The shell command is a silent no-op before Android 9.
    private fun commandsFor(on: Boolean): List<CommandSet> {
        val broadcast = CommandSet(
            "[ \"\$(id -u)\" = 0 ] && settings put global airplane_mode_on ${if (on) 1 else 0} && " +
                "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $on"
        )
        if (Build.VERSION.SDK_INT < 28) return listOf(broadcast)
        return listOf(
            CommandSet("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}"),
            broadcast
        )
    }

    override val statusCommands = listOf(
        CommandSet("settings get global airplane_mode_on")
    )

    override fun parseStatusOutput(output: String): FeatureState {
        return StatusParsingUtils.parseStandardOutput(output)
    }
}
