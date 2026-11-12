package org.kaijinlab.hush.privacy

import org.kaijinlab.hush.data.*
import org.kaijinlab.hush.root.RootManager
import org.kaijinlab.hush.util.StatusParsingUtils

open class MobileDataToggle(rootManager: RootManager) : BasePrivacyToggle(rootManager) {

    companion object {
        // Compiled once: parseStatusOutput runs on every read-back attempt.
        private val TELEPHONY_DATA_ENABLED = Regex("isdataenabled=(true|false)")
    }

    override val feature = PrivacyFeature.MOBILE_DATA
    override val featureName = "Mobile Data"

    // No mobile_data fallback: telephony only stores that key (per SIM on dual-SIM phones), and the
    // ANY_DATA_STATE broadcast is a state report, not a command.
    override val enableCommands = listOf(
        CommandSet("svc data enable")
    )

    override val disableCommands = listOf(
        CommandSet("svc data disable")
    )

    // The data SIM's slot comes from the newest line of the dump's rolling log; once that rolls away, its own key is
    // read before slot 0's line. Each command fails on no match so the next runs, and none calls exit: root keeps one shell.
    override val statusCommands = listOf(
        CommandSet(
            "SUBID=\$(dumpsys telephony.registry | grep -m1 'mActiveDataSubId=' | sed 's/.*=//'); " +
                "PHONEID=\$(dumpsys telephony.registry | grep \"phoneId=[0-9]* subId=\$SUBID \" | sed -nE '\$s/.*phoneId=([0-9]+).*/\\1/p'); " +
                "dumpsys telephony.registry | awk -v id=\"\$PHONEID\" -v s=\"\$SUBID\" " +
                "'/^ *Phone Id=/ {f = id != \"\" && s ~ /^[0-9]+\$/ && \$0 ~ (\"^ *Phone Id=\" id \"\$\")} f && /mIsDataEnabled=/ {print; found=1; exit} END {exit !found}'"
        ),
        CommandSet(
            "SUBID=\$(dumpsys telephony.registry | grep -m1 'mActiveDataSubId=' | sed 's/.*=//'); " +
                "case \"\$SUBID\" in ''|-*) false ;; *) settings get global mobile_data\$SUBID | grep -x '[01]' ;; esac"
        ),
        CommandSet("dumpsys telephony.registry | grep -m1 mIsDataEnabled"),
        CommandSet("settings get global mobile_data")
    )

    // Public (widened from the base class's protected, which Kotlin allows) so the regression
    // test for this exact parsing logic (MobileDataToggleTest.kt) can call it directly instead
    // of needing a subclass or reflection just to reach it.
    public override fun parseStatusOutput(output: String): FeatureState {
        // The telephony line looks like "mIsDataEnabled=true". Its field NAME
        // contains the word "enabled" regardless of the actual value, so the
        // generic parser (which just looks for the word "enabled" anywhere)
        // would misread "mIsDataEnabled=false" as enabled. Check the value
        // after the '=' explicitly instead.
        val telephonyValue = TELEPHONY_DATA_ENABLED
            .find(output.lowercase())
            ?.groupValues?.get(1)
        if (telephonyValue != null) {
            return if (telephonyValue == "true") FeatureState.ENABLED else FeatureState.DISABLED
        }
        return StatusParsingUtils.parseStandardOutput(output)
    }
}
