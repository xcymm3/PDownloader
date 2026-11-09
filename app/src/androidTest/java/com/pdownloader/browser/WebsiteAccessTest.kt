package com.pdownloader.browser

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WebsiteAccessTest {
    @Test fun imageUploadOpensTheSystemPickerAndReturnsCancellationToThePage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("image/*")
        }
        val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        val cancelled = CountDownLatch(1)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    activity.selectSection(0)
                    activity.navigateHome()
                    activity.openNewTab("https://pdownloader.test/video")
                }
                val deadline = System.currentTimeMillis() + 10000
                var loaded = false
                while (!loaded && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        loaded = findWebView(activity.window.decorView)?.let { it.url == "https://pdownloader.test/video" && it.progress == 100 } == true
                    }
                    if (!loaded) Thread.sleep(100)
                }
                assertTrue(loaded)
                scenario.onActivity { activity ->
                    val web = requireNotNull(findWebView(activity.window.decorView))
                    val params = object : FileChooserParams() {
                        override fun getMode() = MODE_OPEN
                        override fun getAcceptTypes() = arrayOf("image/png")
                        override fun isCaptureEnabled() = false
                        override fun getTitle() = null
                        override fun getFilenameHint() = null
                        override fun createIntent() = Intent(Intent.ACTION_GET_CONTENT)
                    }
                    assertTrue(requireNotNull(web.webChromeClient).onShowFileChooser(web, { if (it == null) cancelled.countDown() }, params))
                }
                assertTrue(cancelled.await(5, TimeUnit.SECONDS))
                assertEquals(1, monitor.hits)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView && view.isShown) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

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
