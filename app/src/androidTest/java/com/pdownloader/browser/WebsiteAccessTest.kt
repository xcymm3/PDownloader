package com.pdownloader.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebsiteAccessTest {
    @Test fun grantsAreScopedToTheSecureOriginAndIndividualCapability() {
        assertEquals("https://example.com", secureSite("https://Example.com:443/watch?q=1"))
        assertEquals("https://example.com:8443", secureSite("https://example.com:8443/watch"))
        assertNull(secureSite("http://example.com/"))
        assertNull(secureSite("https://example.com@other.example/"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SitePermissionStore(context)
        val site = "https://site-permission-test.example"
        try {
            store.remember(site, setOf(SiteCapability.CAMERA, SiteCapability.MICROPHONE))
            val reopened = SitePermissionStore(context)
            assertTrue(reopened.allows(site, SiteCapability.CAMERA))
            assertFalse(reopened.allows("$site:8443", SiteCapability.CAMERA))
            assertFalse(reopened.allows(site, SiteCapability.LOCATION))
            reopened.revoke(site, SiteCapability.CAMERA)
            assertFalse(store.allows(site, SiteCapability.CAMERA))
            assertTrue(store.allows(site, SiteCapability.MICROPHONE))
        } finally {
            SiteCapability.entries.forEach { store.revoke(site, it) }
        }
    }
}
