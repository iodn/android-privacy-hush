package org.kaijinlab.hush.util

import org.junit.Assert.*
import org.junit.Test

class MapsUsageDetectorTest {
    @Test fun `location foreground service keeps background navigation active without GPS fixes`() {
        assertTrue(MapsUsageDetector.hasNavigationService("""
            ACTIVITY MANAGER SERVICES
              * ServiceRecord{1 u0 com.google.android.apps.maps/.NavigationService}
                isForeground=true foregroundId=1 types=0x00000008
        """.trimIndent()))
    }
    @Test fun `offline map download and bound or cached services are not navigation`() {
        assertFalse(MapsUsageDetector.hasNavigationService("""
              * ServiceRecord{1 u0 com.google.android.apps.maps/.DownloadService}
                isForeground=true foregroundId=1 types=0x00000001
              * ServiceRecord{2 u0 com.google.android.apps.maps/.NavigationService}
                isForeground=false foregroundId=0 types=0x00000008
        """.trimIndent()))
        assertFalse(MapsUsageDetector.hasNavigationService("ACTIVITY MANAGER SERVICES\n(nothing)"))
    }
    @Test fun `legacy navigation foreground services have no type field`() {
        assertTrue(MapsUsageDetector.hasNavigationService("* ServiceRecord{1 maps/.NavigationService}\n  isForeground=true foregroundId=1"))
    }
    @Test fun `running fine location and high power operations count as active`() {
        for (op in listOf("FINE_LOCATION", "COARSE_LOCATION", "MONITOR_HIGH_POWER_LOCATION")) {
            assertTrue(MapsUsageDetector.hasActiveLocation("    Package com.google.android.apps.maps:\n      $op (allow):\n        Running start at: -1s"))
        }
    }
    @Test fun `old accesses passive bookkeeping and other operations do not count`() {
        assertFalse(MapsUsageDetector.hasActiveLocation("""
                Package com.google.android.apps.maps:
                  FINE_LOCATION (allow):
                    Access: [top-s] -1s000ms
                  MONITOR_LOCATION (allow):
                    Running start at: -1s
                  RECORD_AUDIO (allow):
                    Running start at: -1s
        """.trimIndent()))
    }
}
