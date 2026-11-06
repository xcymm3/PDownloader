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

    @Test fun fullscreenLocksPortraitAndRestoresOrientationExactlyOnce() {
        launch().use { scenario ->
            scenario.onActivity { activity ->
                val web = descendants(activity.window.decorView).filterIsInstance<WebView>().first()
                val client = web.webChromeClient!!
                val original = activity.requestedOrientation
                var hidden = 0
                client.onShowCustomView(FrameLayout(activity), WebChromeClient.CustomViewCallback { hidden++ })
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
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

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
