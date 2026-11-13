package com.pdownloader.browser

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AesHlsDownloadTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var server: FixtureServer
    private lateinit var repository: DownloadRepository
    private lateinit var engine: DownloadEngine
    private val ids = mutableListOf<String>()
    private val keyOne = ByteArray(16) { (it + 1).toByte() }
    private val keyTwo = ByteArray(16) { (it + 51).toByte() }
    private val requestedUrls = java.util.concurrent.CopyOnWriteArrayList<String>()
    private fun asset(name: String) = context.assets.open("media-fixture/$name").use { it.readBytes() }
    private fun iv(number: Int) = ByteArray(16).apply { this[15] = number.toByte() }
    private fun encrypted(plain: ByteArray, key: ByteArray, iv: ByteArray, badPadding: Boolean = false): ByteArray {
        val data = if (badPadding) plain.copyOf((plain.size / 16 + 1) * 16) else plain
        return Cipher.getInstance(if (badPadding) "AES/CBC/NoPadding" else "AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }.doFinal(data)
    }
    @Before fun setup() {
        server = FixtureServer(context)
        repository = DownloadRepository.get(context)
        engine = DownloadEngine(repository, MediaHttp(cookieForUrl = {
            if (it.startsWith(server.base + "/aes/key")) "fixture=yes" else null
        }, allowLocalTests = true))
    }
    @After fun teardown() {
        ids.forEach { id -> repository.update(id) { it.copy(state = TaskState.CANCELLED) }; repository.forget(id, true) }
        server.close()
    }
    private fun task(query: String = "", public: Boolean = false): DownloadTask = repository.enqueue("测试媒体", MediaVariant(server.base + "/aes/list.m3u8$query", MediaKind.HLS),
        RequestContext(server.base + "/watch", "FixtureAgent", credentialOrigin = origin(server.base), authorization = "Bearer fixture"), saveToPublic = public)
        .first.also { ids.add(it.id) }

    private fun configure(profile: String = "normal", rejectThird: AtomicBoolean = AtomicBoolean(false)) {
        val plain = (0..3).map { asset("segment-${it.toString().padStart(3, '0')}.ts") }
        val cipherParts = plain.mapIndexed { index, bytes ->
            if (index == 3) bytes else encrypted(bytes, if (index < 2) keyOne else keyTwo, if (index < 2) iv(7 + index) else iv(0xf1), profile == "padding")
                .let { if (profile == "truncated") it.copyOf(it.size - 1) else it }
        }
        val manifest = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:1
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-KEY:METHOD=AES-128,URI="key-one?sig=a%2Bb"
            #EXTINF:1,
            segment-000.enc
            #EXTINF:1,
            segment-001.enc
            #EXT-X-KEY:METHOD=AES-128,URI="key-two",IV=0xf1
            #EXTINF:1,
            segment-002.enc
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:1,
            segment-003.enc
            #EXT-X-ENDLIST
        """.trimIndent()
        server.customResponse = { url, headers ->
            requestedUrls.add(url)
            val path = url.substringBefore('?')
            when {
                path == "/aes/list.m3u8" -> FixtureResponse(manifest.toByteArray(), "application/vnd.apple.mpegurl")
                path.startsWith("/aes/key-") -> {
                    if (headers["cookie"] != "fixture=yes" || headers["authorization"] != "Bearer fixture") FixtureResponse(ByteArray(0), status = 403)
                    else FixtureResponse(if (profile == "short-key") keyOne.copyOf(15) else if (profile == "long-key") keyOne.copyOf(17) else if (path.endsWith("one")) keyOne else keyTwo)
                }
                path.startsWith("/aes/segment-") -> {
                    val index = path.substringAfter("segment-").substringBefore('.').toInt()
                    if (index == 2 && rejectThird.get()) FixtureResponse(ByteArray(0), status = 503)
                    else FixtureResponse(cipherParts[index])
                }
                else -> null
            }
        }
    }
    private fun assertPlainParts(task: DownloadTask, parts: List<ByteArray> = (0..3).map { asset("segment-${it.toString().padStart(3, '0')}.ts") }) {
        assertEquals(task.error, TaskState.COMPLETE, task.state)
        parts.forEachIndexed { index, bytes -> assertArrayEquals(bytes, File(repository.folder(task.id), "part-$index.bin").readBytes()) }
        val local = File(repository.folder(task.id), task.output).readText()
        assertFalse(local.contains("EXT-X-KEY")); assertFalse(local.contains("http")); assertFalse(local.contains("fixture"))
        assertFalse(repository.folder(task.id).listFiles().orEmpty().any { it.name.endsWith(".key") })
    }
    @Test fun aes128WithRotatingKeysExplicitAndSequenceIvsBecomesOrdinaryOfflineVideo() {
        configure()
        val task = task()
        engine.run(task.id)
        val completed = repository.get(task.id)!!
        assertPlainParts(completed)
        assertEquals("", completed.error)
        assertEquals(1, requestedUrls.count { it.startsWith("/aes/key-one") })
        assertTrue(requestedUrls.contains("/aes/key-one?sig=a%2Bb"))
        assertEquals(1, requestedUrls.count { it.startsWith("/aes/key-two") })
        server.close()
        assertOfflineReady(repository.playbackUri(completed))
    }
    @Test fun aes128ResumeReusesOnlyCompletedPlaintextParts() {
        val reject = AtomicBoolean(true)
        configure(rejectThird = reject)
        val task = task()
        engine.run(task.id)
        assertEquals(TaskState.FAILED, repository.get(task.id)!!.state)
        assertArrayEquals(asset("segment-000.ts"), File(repository.folder(task.id), "part-0.bin").readBytes())
        assertFalse(File(repository.folder(task.id), "part-2.bin").exists())
        assertFalse(File(repository.folder(task.id), "offline.m3u8").exists())
        reject.set(false)
        repository.update(task.id) { it.copy(state = TaskState.QUEUED) }
        engine.run(task.id)
        assertPlainParts(repository.get(task.id)!!)
        assertEquals(1, requestedUrls.count { it == "/aes/segment-000.enc" })
        assertEquals(1, requestedUrls.count { it.startsWith("/aes/key-one") })
    }
    @Test fun badKeysIncompleteCiphertextAndBadPaddingNeverBecomeCompleted() {
        for (profile in listOf("short-key", "long-key", "truncated", "padding")) {
            configure(profile)
            val task = task("?invalid=$profile")
            engine.run(task.id)
            val failed = repository.get(task.id)!!
            assertEquals(profile, TaskState.FAILED, failed.state)
            assertTrue(failed.output.isEmpty()); assertTrue(failed.publicUri.isEmpty())
            assertFalse(File(repository.folder(task.id), "part-0.bin").exists())
            assertFalse(File(repository.folder(task.id), "offline.m3u8").exists())
        }
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test fun publicZipContainsOnlyOrdinaryPlayablePartsAndNoKeyReferences() {
        configure()
        val task = task(public = true)
        engine.run(task.id)
        val completed = repository.get(task.id)!!
        assertPlainParts(completed)
        assertTrue(completed.publicUri.isNotBlank())
        val entries = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(context.contentResolver.openInputStream(Uri.parse(completed.publicUri))!!).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() }
        }
        assertEquals(setOf("offline.m3u8", "part-0.bin", "part-1.bin", "part-2.bin", "part-3.bin"), entries.keys)
        assertFalse(entries.getValue("offline.m3u8").decodeToString().contains("EXT-X-KEY"))
        assertArrayEquals(asset("segment-000.ts"), entries.getValue("part-0.bin"))
        server.close()
        assertOfflineReady(repository.playbackUri(completed))
    }
    @Test fun aes128EncryptedFmp4InitAndByteRangeSegmentsWorkOffline() {
        val original = HlsPlaylist.parse(asset("single.m3u8").decodeToString(), server.base + "/single.m3u8")
        val file = asset("single.mp4")
        val plain = original.parts.map { file.copyOfRange(it.offset.toInt(), (it.offset + it.length).toInt()) }
        val encrypted = plain.map { encrypted(it, keyOne, iv(1)) }
        val bundle = encrypted.fold(ByteArray(0)) { bytes, part -> bytes + part }
        val manifest = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:1\n#EXT-X-KEY:METHOD=AES-128,URI=\"key-one\",IV=0x1\n")
            append("#EXT-X-MAP:URI=\"bundle\",BYTERANGE=\"${encrypted[0].size}@0\"\n")
            var offset = encrypted[0].size
            encrypted.drop(1).forEach { bytes -> append("#EXTINF:1,\n#EXT-X-BYTERANGE:${bytes.size}@$offset\nbundle\n"); offset += bytes.size }
            append("#EXT-X-ENDLIST\n")
        }
        server.customResponse = { url, _ -> when (url.substringBefore('?')) {
            "/aes/list.m3u8" -> FixtureResponse(manifest.toByteArray(), "application/vnd.apple.mpegurl")
            "/aes/key-one" -> FixtureResponse(keyOne)
            "/aes/bundle" -> FixtureResponse(bundle)
            else -> null
        } }
        val task = task()
        engine.run(task.id)
        val completed = repository.get(task.id)!!
        assertPlainParts(completed, plain)
        assertEquals(encrypted.size, server.requests.count { it.first == "/aes/bundle" && it.second["range"] != null })
        server.close()
        assertOfflineReady(repository.playbackUri(completed))
    }
    private fun assertOfflineReady(uri: Uri) {
        val ready = CountDownLatch(1)
        var player: ExoPlayer? = null
        var error: PlaybackException? = null
        var duration = 0L
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build().also { engine ->
                engine.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) { duration = engine.duration; ready.countDown() } }
                    override fun onPlayerError(failure: PlaybackException) { error = failure; ready.countDown() }
                })
                engine.setMediaItem(MediaItem.fromUri(uri)); engine.prepare()
            }
        }
        try { assertTrue(ready.await(25, TimeUnit.SECONDS)); assertNull(error); assertTrue(duration in 3500..4500) }
        finally { instrumentation.runOnMainSync { player?.release() } }
    }
}
