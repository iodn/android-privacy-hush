package org.kaijinlab.hush.privacy

import org.kaijinlab.hush.data.PrivacyFeature
import org.kaijinlab.hush.root.RootManager

class CameraToggle(rootManager: RootManager) :
    SensorPrivacyToggle(rootManager, sensorName = "camera", sensorId = 2) {

    override val feature = PrivacyFeature.CAMERA
    override val featureName = "Camera"
}
