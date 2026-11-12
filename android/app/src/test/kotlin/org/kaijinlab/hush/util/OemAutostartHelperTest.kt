package org.kaijinlab.hush.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mapping decides whether the Permissions screen promises a deep-link at all.
 * A component that moved on a newer ROM degrades to the next entry or the App-info
 * screen at runtime; an unmapped manufacturer must simply produce no page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OemAutostartHelperTest {

    @Test
    fun `known vendors map to their pages`() {
        assertEquals("com.miui.securitycenter", OemAutostartHelper.components("Xiaomi").single().packageName)
        assertEquals(2, OemAutostartHelper.components("Huawei").size)
        assertEquals(2, OemAutostartHelper.components("OPPO").size)
        assertEquals(1, OemAutostartHelper.components("vivo").size)
        assertEquals(2, OemAutostartHelper.components("Samsung").size)
        for (vendor in listOf("Redmi", "Poco", "Honor", "Realme", "OnePlus")) {
            assertTrue(vendor, OemAutostartHelper.components(vendor).isNotEmpty())
        }
    }

    @Test
    fun `vendor matching ignores case`() {
        assertEquals(OemAutostartHelper.components("xiaomi"), OemAutostartHelper.components("XIAOMI"))
    }

    @Test
    fun `unknown vendors produce no page so the entry stays hidden`() {
        assertTrue(OemAutostartHelper.components("Google").isEmpty())
        assertTrue(OemAutostartHelper.components("").isEmpty())
    }
}
