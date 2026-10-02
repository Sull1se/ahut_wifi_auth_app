package ahut.wifiauth.android.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalSsidPolicyTest {
    @Test
    fun acceptsOnlyExactWorkSsidsAfterRemovingAndroidQuoteWrapper() {
        assertTrue(PortalSsidPolicy.isWorkSsid("AHUT-FREE"))
        assertTrue(PortalSsidPolicy.isWorkSsid(PortalSsidPolicy.normalize("\"AHUT-wifi6\"")))
        assertFalse(PortalSsidPolicy.isWorkSsid("\"AHUT-FREE\""))
        assertFalse(PortalSsidPolicy.isWorkSsid(PortalSsidPolicy.normalize("\"\"AHUT-FREE\"\"")))
        assertFalse(PortalSsidPolicy.isWorkSsid("ahut-free"))
        assertFalse(PortalSsidPolicy.isWorkSsid("AHUT-FREE "))
        assertFalse(PortalSsidPolicy.isWorkSsid(" AHUT-FREE"))
        assertFalse(PortalSsidPolicy.isWorkSsid("<unknown ssid>"))
        assertFalse(PortalSsidPolicy.isWorkSsid(null))
    }

    @Test
    fun automaticTargetsAreRestrictedWithoutReinstatingRemovedDefault() {
        assertEquals(setOf("AHUT-FREE"), PortalSsidPolicy.DEFAULT_TARGET_SSIDS)
        assertEquals(
            setOf("AHUT-wifi6"),
            PortalSsidPolicy.sanitizeTargets(
                setOf("AHUT-wifi6", "AHUT-Guest", "ahut-free", "\"AHUT-FREE\"")
            )
        )
        assertTrue(PortalSsidPolicy.sanitizeTargets(emptySet()).isEmpty())
    }
}
