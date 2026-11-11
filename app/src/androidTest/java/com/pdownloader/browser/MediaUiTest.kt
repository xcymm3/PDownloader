package com.pdownloader.browser

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.GridView
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdownloader.browser.download.*
import com.pdownloader.browser.media.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Tests native state/binding APIs directly. No touch, keyboard or mouse simulation. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var server: FixtureServer
    private lateinit var repository: DownloadRepository
    private val ids = mutableListOf<String>()
    @Before fun setup() { server = FixtureServer(context); repository = DownloadRepository.get(context) }
    @After fun teardown() {
        ids.forEach { id -> repository.update(id) { it.copy(state = TaskState.CANCELLED) }; repository.forget(id, true) }
        server.close()
    }
    private fun task(path: String, kind: MediaKind): DownloadTask = repository.enqueue("界面回归", MediaVariant(server.base + path, kind), RequestContext(server.base + "/", "UI-Test")).first.also { ids.add(it.id) }

    @Test fun filtersShowExactlyOneSelectedCategoryAndTheMatchingFiles() {
        val video = task("/sample.mp4", MediaKind.VIDEO)
        val photo = task("/picture.png", MediaKind.IMAGE)
        val other = repository.enqueue("notes.txt", MediaVariant(server.base + "/notes.txt", MediaKind.OTHER, mime = "text/plain"), photo.context)
            .first.also { ids.add(it.id) }
        val engine = DownloadEngine(repository, MediaHttp(allowLocalTests = true))
        engine.run(video.id); engine.run(photo.id); engine.run(other.id)
        val downloaded = requireNotNull(repository.get(other.id))
        assertEquals(TaskState.COMPLETE, downloaded.state)
        assertEquals("notes.txt", downloaded.output)
        assertEquals("测试附件\n", File(repository.folder(other.id), downloaded.output).readText())
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra("section", 2)).use { scenario ->
            var screen: DownloadsView? = null
            scenario.onActivity { activity ->
                screen = descendants(activity.window.decorView).filterIsInstance<DownloadsView>().single { it.completedPage }
                screen!!.show(true)
            }
            await { onMain { descendants(screen!!).filterIsInstance<GridView>().single().adapter.count == 1 } }
            for ((category, expectedId) in listOf(DownloadCategory.IMAGE to photo.id, DownloadCategory.VIDEO to video.id, DownloadCategory.OTHER to other.id)) {
                scenario.onActivity { screen!!.setCategory(category) }
                scenario.onActivity {
                    val list = descendants(screen!!).filterIsInstance<GridView>().single()
                    assertEquals(1, list.adapter.count)
                    assertEquals(expectedId, (list.adapter.getItem(0) as DownloadTask).id)
                    val buttons = descendants(screen!!).filterIsInstance<TextView>().filter { it.contentDescription?.startsWith("筛选：") == true }.toList()
                    assertEquals(1, buttons.count { it.isSelected })
                    assertTrue(buttons.single { it.isSelected }.text.contains(category.title))
                }
                if (category == DownloadCategory.IMAGE) capture("download-grid-image")
                else if (category == DownloadCategory.VIDEO) {
                    await { onMain { descendants(screen!!).filterIsInstance<ImageView>().any { it.drawable is android.graphics.drawable.BitmapDrawable } } }
                    capture("download-grid-video")
                }
            }
            scenario.onActivity { screen!!.setCategory(DownloadCategory.ALL); assertEquals(DownloadCategory.VIDEO, screen!!.selectedCategory); assertEquals(1, descendants(screen!!).filterIsInstance<GridView>().single().adapter.count) }
        }
    }

    @Test fun galleryLoadsLocalAndRemoteImagesAndKeepsSharedSelectionAcrossPages() {
        val photo = task("/picture.png", MediaKind.IMAGE)
        DownloadEngine(repository, MediaHttp(allowLocalTests = true)).run(photo.id)
        val completed = repository.get(photo.id)!!
        val local = File(repository.folder(photo.id), completed.output)
        val selected = linkedSetOf("local")
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(android.net.Uri.parse(server.base + "/"))).use { scenario ->
            var viewer: ImageGalleryDialog? = null
            var sheet: MediaSheet? = null
            val images = MediaImages()
            try {
                var host: MainActivity? = null
                scenario.onActivity { host = it }
                await { onMain { descendants(host!!.window.decorView).any { it.contentDescription?.startsWith("本页资源，") == true && it.isShown } } }
                capture("resource-button")
                scenario.onActivity { activity ->
                    val session = ProbeSession("visual-qa", server.base + "/", "UI-Test", "本地资源测试")
                    session.groups["video"] = MediaGroup("video", "本地测试视频", variants = mutableListOf(MediaVariant(server.base + "/sample.mp4", MediaKind.VIDEO, "原始版本", size = 121000, mime = "video/mp4")), context = photo.context, durationMs = 4000)
                    repeat(6) { index ->
                        val url = server.base + "/picture.png?image=$index"
                        session.groups["image-$index"] = MediaGroup("image-$index", "测试图片", image = true, thumbnail = url, variants = mutableListOf(MediaVariant(url, MediaKind.IMAGE, "图片")), context = photo.context)
                    }
                    sheet = MediaSheet(activity, session, images, submit = {}, previewVideo = { _, _ -> }, retry = {})
                    sheet!!.show(false)
                }
                capture("resource-sheet")
                scenario.onActivity { sheet!!.dismiss() }
                scenario.onActivity { activity ->
                    viewer = ImageGalleryDialog(activity, listOf(
                        GalleryPicture("local", "本地图片", file = local),
                        GalleryPicture("remote", "网页图片", url = server.base + "/picture.png", context = photo.context)
                    ), 0, images, isSelected = { it in selected }, select = { id, value -> if (value) selected.add(id) else selected.remove(id) }, selectedCount = { selected.size })
                    viewer!!.show()
                    val root = viewer!!.window!!.decorView.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    assertTrue(Color.alpha((root.background as ColorDrawable).color) in 1..254)
                    assertEquals(0, viewer!!.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    assertTrue(descendants(viewer!!.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked)
                }
                await { onMain { descendants(viewer!!.window!!.decorView).filterIsInstance<ImageView>().any { it.isShown && it.drawable != null } } }
                capture("image-gallery")
                scenario.onActivity { viewer!!.goTo(1) }
                await { onMain { !descendants(viewer!!.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked &&
                    descendants(viewer!!.window!!.decorView).filterIsInstance<ImageView>().any { it.isShown && it.drawable != null } } }
                scenario.onActivity {
                    selected.add("remote"); viewer!!.refreshSelection()
                    assertTrue(descendants(viewer!!.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked)
                    viewer!!.goTo(0)
                }
                await { onMain { descendants(viewer!!.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString().startsWith("1 /") } } }
                scenario.onActivity {
                    assertTrue(descendants(viewer!!.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked)
                    viewer!!.dismiss()
                }
                assertEquals(setOf("local", "remote"), selected)
                assertTrue(local.exists())
            } finally { instrumentation.runOnMainSync { viewer?.dismiss(); sheet?.dismiss(); images.close() } }
        }
    }

    @Test fun mp4AndHlsCanSeekFromTheNativeTimeBarAndRestorePausedPosition() {
        for ((path, kind) in listOf("/sample.mp4" to MediaKind.VIDEO, "/media.m3u8" to MediaKind.HLS)) {
            val video = task(path, kind)
            ActivityScenario.launch<PlayerActivity>(Intent(context, PlayerActivity::class.java).putExtra("preview", DownloadRepository.encode(video).toString())).use { scenario ->
                var activity: PlayerActivity? = null
                scenario.onActivity { a -> activity = a; a.findViewById<PlayerView>(R.id.native_player).player!!.pause() }
                await { onMain { activity!!.canSeek && activity!!.findViewById<PlayerView>(R.id.native_player).player!!.playbackState == Player.STATE_READY } }
                scenario.onActivity { a ->
                    val bar = a.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
                    assertTrue(bar.isEnabled)
                    assertTrue(a.seekToPosition(2000))
                    assertTrue(kotlin.math.abs(a.playbackPositionMs - 2000) < 100)
                }
                scenario.recreate()
                scenario.onActivity { activity = it }
                await { onMain { activity!!.canSeek && activity!!.findViewById<PlayerView>(R.id.native_player).player!!.playbackState == Player.STATE_READY } }
                scenario.onActivity { a ->
                    assertFalse(a.findViewById<PlayerView>(R.id.native_player).player!!.playWhenReady)
                    assertTrue(kotlin.math.abs(a.playbackPositionMs - 2000) < 200)
                    a.setPlayerFullscreen(true)
                    assertTrue(a.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress).isEnabled)
                    assertTrue(a.seekToPosition(3000))
                    assertTrue(kotlin.math.abs(a.playbackPositionMs - 3000) < 100)
                }
                await { var landscape = false; scenario.onActivity { landscape = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }; landscape }
                if (kind == MediaKind.VIDEO) {
                    capture("video-player")
                    scenario.onActivity { a ->
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            val bars = a.window.decorView.rootWindowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars())
                            val controls = a.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)
                            val location = IntArray(2); controls.getLocationOnScreen(location)
                            assertTrue("播放器控件不能与系统导航键重叠", location[1] + controls.height <= a.window.decorView.height - bars.bottom + 2)
                        }
                    }
                }
            }
        }
    }

    @Test fun downloadedVideoNavigationAndProgressSurviveClosingThePlayer() {
        val first = task("/sample.mp4?queue=1", MediaKind.VIDEO)
        val second = task("/sample.mp4?queue=2", MediaKind.VIDEO)
        val engine = DownloadEngine(repository, MediaHttp(allowLocalTests = true))
        engine.run(first.id); engine.run(second.id)
        fun launch() = ActivityScenario.launch<PlayerActivity>(Intent(context, PlayerActivity::class.java).putExtra("task", second.id))
        var host: PlayerActivity? = null
        fun ready() = onMain { host?.canSeek == true && host!!.findViewById<PlayerView>(R.id.native_player).player!!.playbackState == Player.STATE_READY }
        launch().use { scenario ->
            scenario.onActivity { host = it; it.findViewById<PlayerView>(R.id.native_player).player!!.pause() }
            await(::ready)
            scenario.onActivity {
                assertTrue(it.seekToPosition(2000))
                val hint = descendants(it.window.decorView).filterIsInstance<SeekGestureFrame>().single()
                hint.showSeekHint(2000, 4000)
                assertTrue(descendants(hint).filterIsInstance<TextView>().any { view -> view.text == "0:02 / 0:04" })
                assertFalse(descendants(it.window.decorView).filterIsInstance<TextView>().any { view -> view.text == "离线播放" })
            }
            capture("seek-hud")
            scenario.onActivity {
                it.switchVideo(true)
                it.findViewById<PlayerView>(R.id.native_player).player!!.pause()
            }
            await(::ready)
            scenario.onActivity { it.switchVideo(false); it.findViewById<PlayerView>(R.id.native_player).player!!.pause() }
            await(::ready)
            scenario.onActivity { assertTrue(kotlin.math.abs(it.playbackPositionMs - 2000) < 250) }
        }
        launch().use { scenario ->
            scenario.onActivity { host = it; it.findViewById<PlayerView>(R.id.native_player).player!!.pause() }
            await(::ready)
            scenario.onActivity { assertTrue(kotlin.math.abs(it.playbackPositionMs - 2000) < 250) }
        }
    }
    private fun onMain(check: () -> Boolean): Boolean { var result = false; instrumentation.runOnMainSync { result = check() }; return result }
    private fun capture(name: String) {
        // Passive framebuffer capture from the isolated Android test device; no injected input.
        instrumentation.waitForIdleSync(); Thread.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name-${System.currentTimeMillis()}.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PDownloader-ui-qa")
            }
            val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法保存测试截图")
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } else {
            val folder = File(context.getExternalFilesDir(null), "ui-qa").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }
    private fun await(check: () -> Boolean) {
        val until = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < until) { if (check()) return; Thread.sleep(100) }
        fail("等待界面数据或播放器超时")
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
