package org.kaijinlab.hush.data

enum class PrivacyFeature(
    val displayName: String
) {
    WIFI("WiFi"),
    BLUETOOTH("Bluetooth"),
    MOBILE_DATA("Mobile Data"),
    LOCATION("Location Services"),
    NFC("NFC"),
    CAMERA("Camera"),
    MICROPHONE("Microphone"),
    AIRPLANE_MODE("Airplane Mode"),
    BATTERY_SAVER("Battery Saver");

    companion object {
        // Set, not List: PrivacyActionWorker.kt uses these inside `in`/`!in` filter predicates,
        // once per feature being categorised, so a List would mean an O(n) scan every time; a
        // Set makes that O(1). Held as cached vals rather than a setOf() per call, so the sets
        // are built once per process; PrivacyActionWorker.kt still hoists each accessor's result
        // into a local before filtering with it.
        private val SENSOR_FEATURES: Set<PrivacyFeature> = setOf(CAMERA, MICROPHONE)
        private val SYSTEM_MODE_FEATURES: Set<PrivacyFeature> = setOf(AIRPLANE_MODE, BATTERY_SAVER)

        fun getSensorFeatures(): Set<PrivacyFeature> = SENSOR_FEATURES

        fun getSystemModeFeatures(): Set<PrivacyFeature> = SYSTEM_MODE_FEATURES
    }
}

enum class FeatureState {
    ENABLED,
    DISABLED,
    UNKNOWN,
    UNAVAILABLE,
    ERROR
}

data class PrivacyResult(
    val feature: PrivacyFeature,
    val success: Boolean,
    val message: String? = null,
    val commandUsed: String? = null
)

object TimerSettings {
    const val MAX_DELAY_SECONDS = 1800
}

data class CommandSet(
    val primary: String
)
