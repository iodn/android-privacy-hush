package org.kaijinlab.hush.privacy

import org.kaijinlab.hush.data.FeatureState
import org.kaijinlab.hush.data.PrivacyFeature
import org.kaijinlab.hush.data.PrivacyResult

interface PrivacyToggle {
    val feature: PrivacyFeature

    suspend fun enable(): PrivacyResult
    
    suspend fun disable(): PrivacyResult
    
    suspend fun getCurrentState(): FeatureState
}
