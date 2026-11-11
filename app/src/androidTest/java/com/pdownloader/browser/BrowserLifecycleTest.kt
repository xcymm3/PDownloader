package com.pdownloader.browser

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises lifecycle and engine callbacks directly, without touch/keyboard simulation. */
@RunWith(AndroidJUnit4::class)
class BrowserLifecycleTest {
    private fun launch(): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return ActivityScenario.launch(Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://pdownloader.test/video")
        })
    }

    @Test fun fullscreenLocksLandscapeAndRestoresOrientationExactlyOnce() {
        launch().use { scenario ->
            scenario.onActivity { activity ->
                val web = descendants(activity.window.decorView).filterIsInstance<WebView>().first()
                val client = web.webChromeClient!!
                val original = activity.requestedOrientation
                var hidden = 0
                client.onShowCustomView(FrameLayout(activity), WebChromeClient.CustomViewCallback { hidden++ })
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, activity.requestedOrientation)
                var rejected = 0
                client.onShowCustomView(FrameLayout(activity), WebChromeClient.CustomViewCallback { rejected++ })
                assertEquals(1, rejected)
                client.onHideCustomView()
                assertEquals(original, activity.requestedOrientation)
                assertEquals(1, hidden)
                client.onHideCustomView()
                assertEquals(1, hidden)
            }
        }
    }

    @Test fun recreationRestoresTheSelectedTabAddress() {
        launch().use { scenario ->
            scenario.recreate()
            scenario.onActivity { activity ->
                val address = descendants(activity.window.decorView).filterIsInstance<EditText>().single { it.contentDescription == "地址栏" }
                assertEquals("https://pdownloader.test/video", address.text.toString())
                assertTrue(descendants(activity.window.decorView).any { it is WebView })
            }
        }
    }

    @Test fun backRestoresThePreviousPageAfterActivityRecreationAndReturnsToTheOpener() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        FixtureServer(context).use { server ->
            val first = server.base + "/nav-one"
            val second = server.base + "/nav-two"
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW; data = Uri.parse(first)
            }).use { scenario ->
                fun web(activity: MainActivity) = descendants(activity.window.decorView).filterIsInstance<WebView>().single { it.isShown }
                fun awaitPage(url: String) {
                    val deadline = System.currentTimeMillis() + 20000
                    while (System.currentTimeMillis() < deadline) {
                        var loaded = false
                        scenario.onActivity { loaded = web(it).url == url && web(it).progress == 100 }
                        if (loaded) return
                        Thread.sleep(100)
                    }
                    fail("Page did not finish loading")
                }
                awaitPage(first)
                scenario.onActivity { web(it).loadUrl(second) }
                awaitPage(second)
                scenario.recreate()
                awaitPage(second)
                scenario.onActivity { assertTrue(web(it).canGoBack()); it.goBack() }
                awaitPage(first)
                scenario.onActivity { it.openNewTab(second) }
                awaitPage(second)
                scenario.onActivity { assertFalse(web(it).canGoBack()); it.goBack() }
                awaitPage(first)
            }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
