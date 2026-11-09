package com.pdownloader.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdownloader.browser.update.AppRelease
import com.pdownloader.browser.update.ReleaseVersion
import com.pdownloader.browser.update.UpdateProblem
import com.pdownloader.browser.update.parseNewRelease
import com.pdownloader.browser.update.prepareUpdate
import com.pdownloader.browser.update.verifyUpgrade
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class AppUpdateTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val installed = ReleaseVersion(0, 3, 9)
    private val candidate = ReleaseVersion(0, 3, 10)
    private val release = requireNotNull(AppRelease.read("v$candidate", 3, "a".repeat(64)))

    private fun metadata(asset: JSONObject = JSONObject()
        .put("name", release.assetName)
        .put("state", "uploaded")
        .put("size", release.size)
        .put("digest", "sha256:${release.sha256}")
        .put("browser_download_url", release.downloadUrl)
    ) = JSONObject().put("tag_name", release.tag).put("assets", JSONArray().put(asset))

    @Test fun comparesVersionComponentsAndSkipsOldOrUnpublishedReleases() {
        assertEquals(release, parseNewRelease(metadata(), installed))
        assertNull(parseNewRelease(metadata(), candidate))
        assertNull(parseNewRelease(metadata().put("tag_name", "v0.3.8"), installed))
        assertNull(parseNewRelease(metadata().put("prerelease", true), installed))
        assertNull(parseNewRelease(metadata().put("draft", true), installed))
    }

    @Test fun requiresTheOfficialAssetAndItsDigest() {
        assertThrows(UpdateProblem::class.java) { parseNewRelease(metadata(JSONObject()), installed) }
        val wrongUrl = metadata().apply {
            getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://example.com/${release.assetName}")
        }
        assertThrows(UpdateProblem::class.java) { parseNewRelease(wrongUrl, installed) }
        val noDigest = metadata().apply { getJSONArray("assets").getJSONObject(0).remove("digest") }
        assertThrows(UpdateProblem::class.java) { parseNewRelease(noDigest, installed) }
    }

    @Test fun rejectsDamagedDownloadsAndDoesNotExposeThemToTheInstaller() {
        val context = instrumentation.targetContext
        val wrongDigest = assertThrows(UpdateProblem::class.java) {
            prepareUpdate(context, Long.MAX_VALUE, release, byteArrayOf(1, 2, 3).inputStream())
        }
        assertEquals("更新包完整性校验失败，请重新下载", wrongDigest.message)
        val bytes = byteArrayOf(1, 2, 3)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val invalidApk = assertThrows(UpdateProblem::class.java) {
            prepareUpdate(context, Long.MAX_VALUE, release.copy(sha256 = sha), bytes.inputStream())
        }
        assertEquals("无法读取更新包或验证 APK 签名", invalidApk.message)
        assertEquals(false, File(context.cacheDir, "app-updates/${Long.MAX_VALUE}.apk").exists())
    }

    @Test fun rejectsAnUnchangedInstalledApkAndADifferentApplication() {
        val context = instrumentation.targetContext
        val unchanged = assertThrows(UpdateProblem::class.java) {
            verifyUpgrade(context, File(context.applicationInfo.sourceDir), requireNotNull(ReleaseVersion.parse(BuildConfig.VERSION_NAME)))
        }
        assertEquals("更新包版本与 GitHub 发布信息不符，或当前版本已经更新", unchanged.message)
        val unrelated = assertThrows(UpdateProblem::class.java) {
            verifyUpgrade(context, File(instrumentation.context.applicationInfo.sourceDir), candidate)
        }
        assertEquals("更新包的应用 ID 与当前安装不同，已停止安装", unrelated.message)
    }
}
