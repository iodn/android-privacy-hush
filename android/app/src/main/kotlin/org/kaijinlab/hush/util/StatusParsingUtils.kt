package org.kaijinlab.hush.util

import org.kaijinlab.hush.data.FeatureState

object StatusParsingUtils {

    // The same sensor ids (1 and 2) are parsed on every read-back attempt, so each pattern is
    // compiled once and reused instead of rebuilt per call.
    private val sensorPatterns = java.util.concurrent.ConcurrentHashMap<Int, Regex>()

    /**
     * Standard parsing logic for feature status output
     * Handles common patterns across different privacy features
     */
    fun parseStandardOutput(output: String): FeatureState {
        val normalizedOutput = output.lowercase().trim()
        
        return when {
            // Enabled patterns
            normalizedOutput.contains("1") || 
            normalizedOutput.contains("2") || 
            normalizedOutput.contains("enabled") || 
            normalizedOutput.contains("on") ||
            normalizedOutput.contains("connected") -> {
                FeatureState.ENABLED
            }
            
            // Disabled patterns
            normalizedOutput.contains("0") || 
            normalizedOutput.contains("disabled") || 
            normalizedOutput.contains("off") ||
            normalizedOutput.contains("disconnected") -> {
                FeatureState.DISABLED
            }
            
            // Unknown/empty patterns
            normalizedOutput.isEmpty() || 
            normalizedOutput.contains("null") -> {
                FeatureState.UNKNOWN
            }
            
            else -> {
                FeatureState.UNKNOWN
            }
        }
    }
    
    /**
     * Location-specific parsing with additional patterns
     */
    fun parseLocationOutput(output: String): FeatureState {
        val normalizedOutput = output.lowercase().trim()

        return when {
            // Location enabled patterns
            normalizedOutput.contains("3") ||
            normalizedOutput.contains("gps") ||
            normalizedOutput.contains("network") -> {
                FeatureState.ENABLED
            }

            // Location disabled patterns
            normalizedOutput.contains("0") ||
            normalizedOutput.isEmpty() ||
            normalizedOutput.contains("null") -> {
                FeatureState.DISABLED
            }

            else -> FeatureState.UNKNOWN
        }
    }

    /**
     * Parse sensor privacy output from dumpsys sensor_privacy
     *
     * Expected format:
     * sensor=1 (microphone) or sensor=2 (camera)
     * state_type=1 (privacy enabled/blocked) or state_type=2 (privacy disabled/allowed)
     *
     * @param output The output from dumpsys sensor_privacy command
     * @param sensorId The sensor ID to check (1=microphone, 2=camera)
     * @return FeatureState.ENABLED if sensor is allowed (state_type=2),
     *         FeatureState.DISABLED if sensor is blocked (state_type=1),
     *         FeatureState.UNKNOWN if the sensor entry is absent or unreadable
     */
    fun parseSensorPrivacyOutput(output: String, sensorId: Int): FeatureState {
        try {
            // Look for the sensor block in the output
            val sensorPattern = sensorPatterns.getOrPut(sensorId) {
                "sensor=$sensorId[\\s\\S]*?state_type=(\\d+)".toRegex()
            }
            val match = sensorPattern.find(output)

            return if (match != null) {
                val stateType = match.groupValues[1].toIntOrNull()
                when (stateType) {
                    1 -> FeatureState.DISABLED  // Privacy enabled = sensor blocked
                    2 -> FeatureState.ENABLED   // Privacy disabled = sensor allowed
                    else -> FeatureState.UNKNOWN
                }
            } else {
                // A missing entry is not proof that access is allowed.
                FeatureState.UNKNOWN
            }
        } catch (e: Exception) {
            return FeatureState.UNKNOWN
        }
    }
}
