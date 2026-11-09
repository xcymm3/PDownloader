package com.pdownloader.browser

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdownloader.browser.media.BilibiliAdapter
import com.pdownloader.browser.media.ShareMedia
import com.pdownloader.browser.media.SiteAdapter
import com.pdownloader.browser.media.sameBilibiliVideoPage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ShareRecognitionTest {
    private val page = "https://www.bilibili.com/video/BV1xx411c7mD/?p=2"

    @Test fun bilibiliUsesTheRequestedPartAndFirstPartyProgressiveVersions() {
        val requests = mutableListOf<String>()
        val adapter = BilibiliAdapter(loadJson = { url, _ ->
            requests.add(url)
            if (url.contains("web-interface")) JSONObject("""
                {"code":0,"data":{"bvid":"BV1xx411c7mD","title":"Fixture","cid":1,"pic":"https://i0.hdslb.com/cover.jpg",
                "pages":[{"page":1,"cid":1},{"page":2,"cid":42,"part":"Second","duration":5}]}}
            """.trimIndent()) else {
                val quality = if (url.contains("qn=64")) 64 else 16
                JSONObject("""
                    {"code":0,"data":{"quality":$quality,"format":"mp4","accept_quality":[64,16],
                    "durl":[{"url":"https://test.bilivideo.com/video/$quality.mp4","size":2048}]}}
                """.trimIndent())
            }
        }, resolveShort = { _, _ -> page })
        assertTrue(adapter.matches(page))
        assertTrue(sameBilibiliVideoPage(page, "https://m.bilibili.com/video/BV1xx411c7mD/?share_source=copy&p=2"))
        assertFalse(sameBilibiliVideoPage(page, "https://m.bilibili.com/video/BV1xx411c7mD/"))
        assertFalse(adapter.matches("https://bilibili.com.example/video/BV1xx411c7mD/"))
        val result = adapter.recognize("https://b23.tv/fixture", "FixtureAgent") as ShareMedia.Found
        assertEquals(page, result.page)
        assertEquals(2, result.groups.size)
        assertEquals("Fixture · Second", result.title)
        val video = result.groups.first()
        assertEquals(5000L, video.durationMs)
        assertEquals(listOf("720p", "360p"), video.variants.map { it.quality })
        assertEquals(page, video.variants.first().requestContext?.pageUrl)
        assertTrue(requests.filter { it.contains("playurl") }.all { it.contains("cid=42") })
    }

    @Test fun explicitLoginAndUnsupportedShortLinksFallBack() {
        val login = BilibiliAdapter({ _, _ -> JSONObject("""{"code":-101}""") }, { _, _ -> page })
        assertTrue((login.recognize(page, "FixtureAgent") as ShareMedia.OpenPage).message.contains("登录"))
        val short = BilibiliAdapter({ _, _ -> error("Unexpected API call") }, { _, _ -> "https://example.com/" })
        assertTrue(short.recognize("https://b23.tv/fixture", "FixtureAgent") is ShareMedia.OpenPage)
    }

    @Test fun cancellingRecognitionIgnoresAnAlreadyRunningReply() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val worker = AtomicReference<Thread>()
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            lateinit var recognition: ShareRecognition
            try {
                scenario.onActivity { activity ->
                    recognition = ShareRecognition(activity, { _, _, _ -> callbacks.incrementAndGet() }, { _, _ -> callbacks.incrementAndGet() })
                    recognition.start(page, "FixtureAgent", object : SiteAdapter {
                        override fun matches(url: String) = true
                        override fun recognize(url: String, userAgent: String): ShareMedia {
                            worker.set(Thread.currentThread())
                            started.countDown()
                            while (release.count > 0) runCatching { release.await(100, TimeUnit.MILLISECONDS) }
                            returned.countDown()
                            return ShareMedia.Found(page, "Late", emptyList())
                        }
                    })
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                scenario.onActivity { recognition.cancel() }
                release.countDown()
                assertTrue(returned.await(5, TimeUnit.SECONDS))
                scenario.onActivity { recognition.close() }
                worker.get().join(5000)
                assertFalse(worker.get().isAlive)
                instrumentation.waitForIdleSync()
                assertEquals(0, callbacks.get())
            } finally {
                release.countDown()
                scenario.onActivity { recognition.close() }
            }
        }
    }
}
