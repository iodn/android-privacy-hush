package org.kaijinlab.hush.privacy

import org.kaijinlab.hush.data.PrivacyFeature
import org.kaijinlab.hush.root.RootManager

class MicrophoneToggle(rootManager: RootManager) :
    SensorPrivacyToggle(rootManager, sensorName = "microphone", sensorId = 1) {

    override val feature = PrivacyFeature.MICROPHONE
    override val featureName = "Microphone"
}
