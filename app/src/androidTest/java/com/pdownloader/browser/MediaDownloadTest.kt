package com.pdownloader.browser

import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import android.content.Intent
import com.pdownloader.browser.download.*
import com.pdownloader.browser.media.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaDownloadTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var server: FixtureServer
    private lateinit var repository: DownloadRepository
    private lateinit var engine: DownloadEngine
    private val ids = mutableListOf<String>()
    @Before fun setup() {
        server = FixtureServer(context)
        repository = DownloadRepository.get(context)
        engine = DownloadEngine(repository, MediaHttp(allowLocalTests = true))
    }
    @After fun teardown() {
        ids.forEach { id -> repository.update(id) { it.copy(state = TaskState.CANCELLED) }; repository.forget(id, true) }
        server.close()
    }
    private fun enqueue(path: String, kind: MediaKind = MediaKind.VIDEO): DownloadTask = repository.enqueue("测试媒体", MediaVariant(server.base + path, kind), RequestContext(server.base + "/", "FixtureAgent")).first.also { ids.add(it.id) }
    private fun sample() = context.assets.open("media-fixture/sample.mp4").use { it.readBytes() }

    @Test fun directDownloadIsByteExactAndDuplicateDoesNotCreateAnotherTask() {
        val task = enqueue("/sample.mp4")
        val duplicate = repository.enqueue(task.title, task.variant, task.context)
        assertFalse(duplicate.second)
        assertEquals(task.id, duplicate.first.id)
        engine.run(task.id)
        val completed = repository.get(task.id)!!
        assertEquals(TaskState.COMPLETE, completed.state)
        assertArrayEquals(sample(), File(repository.folder(task.id), completed.output).readBytes())
        assertEquals("FixtureAgent", server.requests.last().second["user-agent"])
        repository.forget(task.id, false)
        assertEquals(TaskState.COMPLETE, repository.get(task.id)!!.state)
        assertTrue(File(repository.folder(task.id), completed.output).exists())
        assertEquals(1, repository.all().count { it.id == task.id })
    }
    @Test fun downloadedImageIsAValidLocalBitmap() {
        val task = enqueue("/picture.png", MediaKind.IMAGE)
        engine.run(task.id)
        val completed = repository.get(task.id)!!
        assertEquals(TaskState.COMPLETE, completed.state)
        assertTrue(completed.output.endsWith(".png"))
        val bitmap = android.graphics.BitmapFactory.decodeFile(File(repository.folder(task.id), completed.output).path)
        assertNotNull(bitmap)
        assertEquals(1, bitmap.width)
        assertEquals(1, bitmap.height)
        bitmap.recycle()
    }

    @Test fun legacyFileDownloadIsReadAsOtherWithoutChangingItsPath() {
        val legacy = DownloadTask("legacy", "报告", MediaVariant(server.base + "/report", MediaKind.VIDEO, mime = "application/pdf"),
            RequestContext(server.base + "/", "FixtureAgent"), state = TaskState.COMPLETE, output = "报告.bin", publicUri = "content://example/report")
        val restored = DownloadRepository.decode(DownloadRepository.encode(legacy).toString())
        assertEquals(MediaKind.OTHER, restored.variant.kind)
        assertEquals(legacy.output, restored.output)
        assertEquals(legacy.publicUri, restored.publicUri)
        assertEquals(MediaKind.HLS, downloadKind(server.base + "/movie.m3u8", "application/octet-stream"))
        assertEquals(MediaKind.VIDEO, downloadKind(server.base + "/get", "application/octet-stream", "movie.mp4"))
        assertEquals(MediaKind.IMAGE, downloadKind(server.base + "/get", "image/png"))
        assertEquals(MediaKind.OTHER, downloadKind(server.base + "/get", "audio/mpeg"))
    }
    @Test fun foregroundDownloadSurvivesBrowserActivityDestruction() {
        val task = enqueue("/slow.mp4")
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { it.startForegroundService(Intent(it, BrowserDownloadService::class.java)) }
        }
        await(25000) { repository.get(task.id)?.state == TaskState.COMPLETE }
        val completed = repository.get(task.id)!!
        assertArrayEquals(sample(), File(repository.folder(task.id), completed.output).readBytes())
    }
    @Test fun parallelDownloadsHonorLiveLimitChangesAndCompleteWithoutDuplicateWriters() {
        val settings = DownloadSettings(context)
        val previousLimit = settings.parallelDownloads
        val gates = List(9) { CountDownLatch(1) }
        server.beforeSlowBody = { path ->
            val index = path.substringAfter("parallel=").toInt()
            check(gates[index].await(15, TimeUnit.SECONDS))
        }
        try {
            settings.parallelDownloads = 8
            val tasks = gates.indices.map { enqueue("/slow.mp4?parallel=$it") }
            fun state(index: Int) = repository.get(tasks[index].id)?.state
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { it.startForegroundService(Intent(it, BrowserDownloadService::class.java)) }
                await { (0..7).all { state(it) == TaskState.DOWNLOADING } }
                assertEquals(TaskState.QUEUED, state(8))

                scenario.onActivity { settings.parallelDownloads = 1 }
                (1..7).forEach { gates[it].countDown() }
                await { (1..7).all { state(it) == TaskState.COMPLETE } }
                instrumentation.waitForIdleSync()
                assertEquals(TaskState.DOWNLOADING, state(0))
                assertEquals(TaskState.QUEUED, state(8))

                scenario.onActivity { settings.parallelDownloads = 2 }
                await { state(8) == TaskState.DOWNLOADING }
                gates.forEach { it.countDown() }
                await { tasks.indices.all { state(it) == TaskState.COMPLETE } }
                tasks.forEach { task ->
                    val output = repository.get(task.id)!!.output
                    assertArrayEquals(sample(), File(repository.folder(task.id), output).readBytes())
                }
                assertEquals(9, server.requests.count { it.first == "/slow.mp4" })
            }
        } finally {
            gates.forEach { it.countDown() }
            settings.parallelDownloads = previousLimit
        }
    }
    @Test fun nativePlayerFullscreenUsesLandscapeAndRestoresItsToolbar() {
        val task = enqueue("/sample.mp4")
        ActivityScenario.launch<PlayerActivity>(Intent(context, PlayerActivity::class.java).putExtra("preview", DownloadRepository.encode(task).toString())).use { scenario ->
            scenario.onActivity { activity ->
                activity.setPlayerFullscreen(true)
                assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, activity.requestedOrientation)
                val title = activity.window.decorView.findViewWithContentDescriptionForTest("播放器标题栏")!!
                assertEquals(android.view.View.GONE, title.visibility)
                activity.setPlayerFullscreen(false)
                assertEquals(android.view.View.VISIBLE, title.visibility)
            }
        }
    }
    @Test fun protectedDownloadUsesScopedBrowserCookies() {
        val url = server.base + "/protected.mp4"
        val cookieReady = CountDownLatch(1)
        instrumentation.runOnMainSync {
            android.webkit.CookieManager.getInstance().setCookie(url, "fixture=yes; Path=/protected.mp4") { cookieReady.countDown() }
        }
        assertTrue(cookieReady.await(5, TimeUnit.SECONDS))
        val task = enqueue("/protected.mp4")
        DownloadEngine(repository, MediaHttp({ android.webkit.CookieManager.getInstance().getCookie(it) }, true)).run(task.id)
        assertEquals(TaskState.COMPLETE, repository.get(task.id)!!.state)
        assertTrue(server.requests.last { it.first == "/protected.mp4" }.second["cookie"].orEmpty().contains("fixture=yes"))
    }
    @Test fun resumeValidatesRangesAndRestartsWhenRangeIsIgnored() {
        listOf("/sample.mp4", "/ignore-range.mp4", "/changed-range.mp4").forEach { path ->
            val task = enqueue(path)
            repository.folder(task.id).mkdirs()
            File(repository.folder(task.id), "transfer.part").writeBytes(sample().copyOfRange(0, 1000))
            repository.update(task.id) { it.copy(downloaded = 1000, validator = "\"fixture-v1\"") }
            engine.run(task.id)
            val finished = repository.get(task.id)!!
            assertEquals(TaskState.COMPLETE, finished.state)
            assertArrayEquals(sample(), File(repository.folder(task.id), finished.output).readBytes())
            val requests = server.requests.filter { it.first == path }
            assertEquals("bytes=1000-", requests.first().second["range"])
            if (path == "/changed-range.mp4") assertNull(requests.last().second["range"])
        }
    }

    @Test fun failedTasksKeepTheirIdentityAndNeverReusePartsAfterAChangedAddress() {
        val login = enqueue("/login.mp4")
        engine.run(login.id)
        assertEquals(DownloadFailure.LOGIN, repository.get(login.id)?.failure)
        val forbidden = enqueue("/protected.mp4")
        engine.run(forbidden.id)
        assertEquals(DownloadFailure.FORBIDDEN, repository.get(forbidden.id)?.failure)

        val original = enqueue("/expired", MediaKind.HLS)
        engine.run(original.id)
        assertEquals(DownloadFailure.SOURCE, repository.get(original.id)?.failure)
        val replacement = original.variant.copy(url = server.base + "/media.m3u8")
        val folder = repository.folder(original.id)
        val playlist = context.assets.open("media-fixture/media.m3u8").bufferedReader().use { it.readText() }
        File(folder, "playlist.fingerprint").writeText(stableId(replacement.url + playlist))
        File(folder, "part-0.bin").writeText("stale")
        assertTrue(repository.recover(original.id, replacement, original.context))
        assertFalse(repository.recover(original.id, replacement, original.context))
        assertFalse(repository.enqueue(original.title, replacement, original.context).second)
        engine.run(original.id)
        val recovered = requireNotNull(repository.get(original.id))
        assertEquals(TaskState.COMPLETE, recovered.state)
        assertEquals(original.context.pageUrl, recovered.context.pageUrl)
        val firstPart = HlsPlaylist.parse(playlist, replacement.url).parts.first()
        val bytes = context.assets.open("media-fixture/" + firstPart.url.substringAfterLast('/')).use { it.readBytes() }
        assertArrayEquals(bytes, File(folder, "part-0.bin").readBytes())
    }
    @Test fun pauseResumeAndCancelKeepTemporaryFilesConsistent() {
        val task = enqueue("/slow.mp4")
        val thread = Thread { engine.run(task.id) }.apply { start() }
        await { repository.get(task.id)?.state == TaskState.DOWNLOADING && File(repository.folder(task.id), "transfer.part").length() > 0 }
        repository.update(task.id) { it.copy(state = TaskState.PAUSED) }
        thread.join(10000)
        assertFalse(thread.isAlive)
        assertEquals(TaskState.PAUSED, repository.get(task.id)!!.state)
        repository.update(task.id) { it.copy(state = TaskState.QUEUED) }
        engine.run(task.id)
        assertEquals(TaskState.COMPLETE, repository.get(task.id)!!.state)
        val cancelled = enqueue("/slow.mp4?cancel=1")
        val second = Thread { engine.run(cancelled.id) }.apply { start() }
        await { repository.get(cancelled.id)?.state == TaskState.DOWNLOADING }
        repository.update(cancelled.id) { it.copy(state = TaskState.CANCELLED) }
        second.join(10000)
        assertFalse(second.isAlive)
        assertFalse(repository.folder(cancelled.id).exists())
    }
    @Test fun hlsResumesCompletedPartsAndPreparesForOfflinePlayback() {
        val task = enqueue("/media.m3u8", MediaKind.HLS)
        server.rejectSegment = true
        engine.run(task.id)
        assertEquals(TaskState.FAILED, repository.get(task.id)!!.state)
        assertFalse(File(repository.folder(task.id), "offline.m3u8").exists())
        assertTrue(File(repository.folder(task.id), "part-0.bin").exists())
        server.rejectSegment = false
        repository.update(task.id) { it.copy(state = TaskState.QUEUED) }
        engine.run(task.id)
        val completed = repository.get(task.id)!!
        assertEquals(TaskState.COMPLETE, completed.state)
        assertEquals(1, server.requests.count { it.first == "/segment-000.ts" })
        val local = File(repository.folder(task.id), completed.output)
        assertFalse(local.readText().contains("http"))
        assertOfflineReady(local)
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test fun publicMediaIsIndexedPlayableAndRemovedWithItsFile() {
        val resolver = context.contentResolver
        fun download(path: String, kind: MediaKind): DownloadTask {
            val task = repository.enqueue("公共下载测试", MediaVariant(server.base + path, kind),
                RequestContext(server.base, "FixtureAgent"), saveToPublic = true).first
            ids.add(task.id)
            engine.run(task.id)
            val completed = repository.get(task.id)!!
            assertEquals(completed.error, TaskState.COMPLETE, completed.state)
            val uri = Uri.parse(completed.publicUri)
            assertEquals("content", uri.scheme)
            val columns = arrayOf(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.provider.MediaStore.MediaColumns.IS_PENDING)
            resolver.query(uri, columns, null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Download/PDownloader/", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
            return completed
        }

        val video = download("/sample.mp4", MediaKind.VIDEO)
        val videoUri = repository.playbackUri(video)
        assertArrayEquals(sample(), resolver.openInputStream(videoUri)!!.use { it.readBytes() })
        assertFalse(File(repository.folder(video.id), video.output).exists())
        assertOfflineReady(videoUri)
        repository.forget(video.id, false)
        assertEquals(video.publicUri, repository.get(video.id)?.publicUri)
        repository.forget(video.id, true)
        assertFalse(runCatching { resolver.openInputStream(videoUri)?.use { true } == true }.getOrDefault(false))

        val image = download("/picture.png", MediaKind.IMAGE)
        resolver.openInputStream(repository.playbackUri(image))!!.use { input ->
            val bitmap = android.graphics.BitmapFactory.decodeStream(input)
            assertNotNull(bitmap)
            assertEquals(1, bitmap.width)
            bitmap.recycle()
        }

        val hls = download("/media.m3u8", MediaKind.HLS)
        val zipUri = Uri.parse(hls.publicUri)
        assertEquals("application/zip", resolver.getType(zipUri))
        java.util.zip.ZipInputStream(resolver.openInputStream(zipUri)!!).use { zip ->
            assertEquals("offline.m3u8", zip.nextEntry.name)
            assertFalse(zip.readBytes().decodeToString().contains("http"))
            assertTrue(zip.nextEntry.name.startsWith("part-"))
        }
        assertOfflineReady(repository.playbackUri(hls))
    }
    @Test fun fmp4ByteRangesDownloadAndPrepareOffline() {
        val task = enqueue("/single.m3u8", MediaKind.HLS)
        engine.run(task.id)
        val completed = repository.get(task.id)!!
        assertEquals(completed.error, TaskState.COMPLETE, completed.state)
        assertTrue(server.requests.filter { it.first == "/single.mp4" }.all { it.second.containsKey("range") })
        assertOfflineReady(File(repository.folder(task.id), completed.output))
    }
    private fun assertOfflineReady(local: File) = assertOfflineReady(Uri.fromFile(local))

    private fun assertOfflineReady(local: Uri) {
        val latch = CountDownLatch(1)
        var player: ExoPlayer? = null
        var failure: PlaybackException? = null
        var duration = 0L
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build().also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) { duration = p.duration; latch.countDown() } }
                    override fun onPlayerError(error: PlaybackException) { failure = error; latch.countDown() }
                })
                p.setMediaItem(MediaItem.fromUri(local))
                p.prepare()
            }
        }
        try {
            assertTrue("Offline HLS did not prepare", latch.await(25, TimeUnit.SECONDS))
            assertNull(failure)
            assertTrue("Expected four seconds of playable offline media", duration in 3500..4500)
        } finally { instrumentation.runOnMainSync { player?.release() } }
    }
    @Test fun invalidEncryptedAndLivePlaylistsNeverBecomeCompleted() {
        listOf("/fake.m3u8", "/encrypted.m3u8", "/live.m3u8").forEach { path ->
            val task = enqueue(path, MediaKind.HLS)
            engine.run(task.id)
            val result = repository.get(task.id)!!
            assertEquals(path, TaskState.FAILED, result.state)
            assertTrue(result.error.isNotBlank())
            assertTrue(result.output.isEmpty())
        }
        assertFalse(server.requests.any { it.first.contains("secret.key") })
    }
    @Test fun domLateInsertionHlsAndImagesAreDiscoveredAndNavigationRetiresCallbacks() {
        var probe: MediaProbe? = null
        var web: WebView? = null
        var session: ProbeSession? = null
        instrumentation.runOnMainSync {
            probe = MediaProbe(context) { }
            web = WebView(context).apply {
                settings.javaScriptEnabled = true
                session = probe!!.begin("tab-fixture", server.base + "/", settings.userAgentString, "Fixture")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? { probe!!.observe(session, request); return null }
                }
            }
            probe!!.attach(session, web); probe!!.resume()
            probe!!.direct(session!!, server.base + "/playlist", "application/vnd.apple.mpegurl", -1)
            web!!.loadUrl(server.base + "/")
        }
        try {
            await(25000) {
                var found = false
                instrumentation.runOnMainSync {
                    val groups = session!!.groups.values
                    found = groups.any { it.image } && groups.any { group -> group.variants.count { it.kind == MediaKind.HLS } == 2 } && groups.any { group -> group.variants.any { it.url.contains("delayed=1") } } && groups.any { group -> group.variants.any { it.url.contains("frame=1") } } && groups.any { group -> group.variants.any { it.url.endsWith("/playlist") && it.kind == MediaKind.HLS } }
                }
                found
            }
            instrumentation.runOnMainSync {
                val old = session!!
                val count = old.groups.size
                probe!!.retire(old)
                probe!!.direct(old, server.base + "/late.mp4", "video/mp4", 20)
                assertEquals(count, old.groups.size)
                val next = probe!!.begin("tab-second", server.base + "/new", web!!.settings.userAgentString, "Next")
                assertTrue(next.groups.isEmpty())
            }
        } finally { instrumentation.runOnMainSync { probe?.close(); web?.destroy() } }
    }
    @Test fun extensionlessPlaylistUsesDocumentEvidenceAndDoesNotLeakAcrossNavigation() {
        val page = server.base + "/opaque-page"
        var probe: MediaProbe? = null
        var web: WebView? = null
        lateinit var session: ProbeSession
        instrumentation.runOnMainSync {
            probe = MediaProbe(context) {}
            web = WebView(context).apply {
                settings.javaScriptEnabled = true
                session = probe!!.begin("opaque-tab", page, settings.userAgentString, "Opaque")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                        probe!!.observe(session, request)
                        return null
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        if (url == page) view.evaluateJavascript("startFixture(); startFixture()", null)
                    }
                }
                loadUrl(page)
            }
            // The network request reaches the observer before the first DOM scan.
        }
        try {
            await { server.requests.any { it.first == "/r/7f2" } }
            instrumentation.runOnMainSync { probe!!.attach(session, web); probe!!.resume() }
            await {
                var found = false
                instrumentation.runOnMainSync {
                    found = session.groups.values.singleOrNull()?.variants?.singleOrNull()?.kind == MediaKind.HLS
                }
                found
            }
            assertTrue(server.requests.any { (path, headers) -> path == "/r/7f2" && headers["range"] == "bytes=0-2047" })
            instrumentation.runOnMainSync {
                probe!!.retire(session)
                val next = probe!!.begin("opaque-tab", server.base + "/next", session.userAgent, "Next")
                probe!!.attach(next, web)
                assertTrue(next.groups.isEmpty())
            }
        } finally { instrumentation.runOnMainSync { probe?.close(); web?.destroy() } }
    }

    private fun await(timeout: Long = 15000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < until) { if (condition()) return; Thread.sleep(100) }
        fail("Timed out waiting for media pipeline")
    }
    private fun android.view.View.findViewWithContentDescriptionForTest(description: String): android.view.View? {
        if (contentDescription == description) return this
        if (this is android.view.ViewGroup) for (i in 0 until childCount) getChildAt(i).findViewWithContentDescriptionForTest(description)?.let { return it }
        return null
    }
}
