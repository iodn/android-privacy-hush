package org.kaijinlab.hush.util

import android.content.Context
import org.kaijinlab.hush.root.RootManager

internal data class MapsUsage(val active: Boolean, val verified: Boolean)

/** Checks Maps itself, not Play services' unrelated location bookkeeping or a cached process. */
internal class MapsUsageDetector(private val context: Context, private val root: RootManager) {
    companion object {
        const val PACKAGE = "com.google.android.apps.maps"
        internal fun hasNavigationService(output: String): Boolean =
            output.split(Regex("(?m)^[ \\t]*\\* ServiceRecord")).any { service ->
                if (!Regex("\\bisForeground=true\\b").containsMatchIn(service)) false
                else {
                    val type = Regex("(?:types|foregroundServiceType)=(0x[0-9a-fA-F]+|[0-9]+)")
                        .find(service)?.groupValues?.get(1)?.let {
                            if (it.startsWith("0x")) it.drop(2).toLongOrNull(16) else it.toLongOrNull()
                        }
                    type == null || type == 0L || type.and(8L) != 0L ||
                        Regex("navigation|guidance", RegexOption.IGNORE_CASE).containsMatchIn(service.lineSequence().firstOrNull().orEmpty())
                }
            }

        internal fun hasActiveLocation(output: String): Boolean {
            var locationOp = false
            var opIndent = 0
            for (line in output.lineSequence()) {
                val header = Regex("^([ \\t]*)([A-Z][A-Z_]+)(?: \\(|:)").find(line)
                if (header != null) {
                    opIndent = header.groupValues[1].length
                    locationOp = header.groupValues[2] in setOf("COARSE_LOCATION", "FINE_LOCATION", "MONITOR_HIGH_POWER_LOCATION")
                } else if (line.isNotBlank() && line.takeWhile { it == ' ' || it == '\t' }.length <= opIndent) {
                    locationOp = false
                }
                if (locationOp && ("Running start at" in line || "running=true" in line)) return true
            }
            return false
        }
    }

    suspend fun read(): MapsUsage {
        val services = root.executeCommand("dumpsys activity services $PACKAGE")
        val serviceText = services.output.joinToString("\n")
        if (services.success && hasNavigationService(serviceText)) return MapsUsage(true, true)
        val apk = "'" + context.applicationInfo.sourceDir.replace("'", "'\\''") + "'"
        val task = root.executeCommand("CLASSPATH=$apk app_process /system/bin org.kaijinlab.hush.root.MapsTaskCommand")
        val taskState = task.output.lastOrNull()?.trim()
        if (task.success && taskState == "MAPS") return MapsUsage(true, true)
        val ops = root.executeCommand("dumpsys appops --package $PACKAGE")
        val opText = ops.output.joinToString("\n")
        if (ops.success && hasActiveLocation(opText)) return MapsUsage(true, true)
        val verified = services.success && serviceText.contains("ACTIVITY MANAGER SERVICES") &&
            task.success && taskState == "OTHER" && ops.success &&
            !opText.contains("Unknown option", true) && !opText.contains("Permission Denial", true) && !opText.contains("Can't find service", true)
        return MapsUsage(false, verified)
    }
}
