package com.pdownloader.browser

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.os.Process
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebView
import java.net.URI
import java.util.Locale

internal enum class SiteCapability(val label: String, val permissions: List<String>) {
    CAMERA("摄像头", listOf(Manifest.permission.CAMERA)),
    MICROPHONE("麦克风", listOf(Manifest.permission.RECORD_AUDIO)),
    LOCATION("位置", listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION));

    fun isGranted(context: Context) = context.checkSelfPermission(permissions.first()) == PERMISSION_GRANTED
}

internal fun secureSite(url: String?): String? = runCatching {
    val uri = URI(url ?: return null)
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    if (!uri.scheme.equals("https", true) || uri.rawUserInfo != null || uri.port !in -1..65535 || uri.port == 0) return null
    "https://$host" + if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
}.getOrNull()

internal class SitePermissionStore(context: Context) {
    private val preferences = context.getSharedPreferences("site_permissions", Context.MODE_PRIVATE)
    private fun key(site: String, capability: SiteCapability) = "$site|${capability.name}"
    fun allows(site: String, capability: SiteCapability) = preferences.getBoolean(key(site, capability), false)

    fun remember(site: String, capabilities: Set<SiteCapability>) {
        preferences.edit().apply { capabilities.forEach { putBoolean(key(site, it), true) } }.apply()
    }

    fun revoke(site: String, capability: SiteCapability) {
        preferences.edit().remove(key(site, capability)).apply()
    }

    fun grants(): Set<Pair<String, SiteCapability>> = preferences.all.keys.mapNotNull { key ->
        val site = key.substringBefore('|')
        SiteCapability.entries.find { it.name == key.substringAfter('|') }?.let { site to it }
    }.toSet()
}

internal class WebsiteAccess(private val activity: Activity, private val isActive: (WebView) -> Boolean) {
    private data class FileRequest(val web: WebView, val callback: ValueCallback<Array<Uri>>)
    private data class SiteRequest(
        val web: WebView, val site: String, val capabilities: Set<SiteCapability>,
        val media: PermissionRequest?, val respond: (Boolean) -> Unit
    )

    private val store = SitePermissionStore(activity)
    private var lastGrants = store.grants()
    private var file: FileRequest? = null
    private var fileInFlight = false
    private var pending: SiteRequest? = null
    private var systemInFlight = false
    private var dialog: AlertDialog? = null

    @Suppress("DEPRECATION")
    fun chooseFiles(web: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
        if (!isActive(web) || fileInFlight || params.mode !in listOf(FileChooserParams.MODE_OPEN, FileChooserParams.MODE_OPEN_MULTIPLE)) {
            callback.onReceiveValue(null)
            return true
        }
        val mimeTypes = params.acceptTypes.flatMap { it.split(',') }.mapNotNull { value ->
            val type = value.trim().lowercase(Locale.ROOT)
            if (type.startsWith('.')) MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.drop(1))
            else type.takeIf { it.contains('/') }
        }.distinct()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeTypes.singleOrNull() ?: "*/*"
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
        }
        file = FileRequest(web, callback)
        fileInFlight = true
        try {
            activity.startActivityForResult(intent, CHOOSE_FILES)
        } catch (_: android.content.ActivityNotFoundException) {
            onFileResult(Activity.RESULT_CANCELED, null)
        }
        return true
    }

    fun onFileResult(resultCode: Int, data: Intent?) {
        fileInFlight = false
        val request = file ?: return
        file = null
        val selected = if (resultCode == Activity.RESULT_OK && isActive(request.web)) buildList {
            data?.clipData?.let { clip -> repeat(clip.itemCount) { add(clip.getItemAt(it).uri) } }
            data?.data?.let(::add)
        }.filterNotNull().distinct().takeIf { it.isNotEmpty() && it.all(::readableSelection) } else null
        request.callback.onReceiveValue(selected?.toTypedArray())
    }

    private fun readableSelection(uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        val authority = uri.authority ?: return false
        if (authority == "${activity.packageName}.files" ||
            activity.packageManager.resolveContentProvider(authority, 0)?.packageName == activity.packageName) return false
        return activity.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PERMISSION_GRANTED
    }

    fun requestMedia(web: WebView, request: PermissionRequest) {
        val resources = request.resources
        val capabilities = resources.mapNotNull {
            when (it) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> SiteCapability.CAMERA
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> SiteCapability.MICROPHONE
                else -> null
            }
        }
        if (resources.isEmpty() || capabilities.size != resources.size) { request.deny(); return }
        ask(web, request.origin.toString(), capabilities.toSet(), request) { granted ->
            if (granted) request.grant(resources) else request.deny()
        }
    }

    fun askLocation(web: WebView, origin: String, respond: (Boolean) -> Unit) {
        ask(web, origin, setOf(SiteCapability.LOCATION), null, respond)
    }

    private fun ask(web: WebView, origin: String, capabilities: Set<SiteCapability>, media: PermissionRequest?, respond: (Boolean) -> Unit) {
        val site = secureSite(origin)
        if (site == null || !isActive(web) || secureSite(web.url) != site || pending != null || systemInFlight) {
            respond(false)
            return
        }
        val request = SiteRequest(web, site, capabilities, media, respond)
        pending = request
        if (capabilities.all { store.allows(site, it) }) requestAndroid(request)
        else {
            dialog = AlertDialog.Builder(activity).setTitle("网站权限")
                .setMessage("$site\n请求使用${capabilities.joinToString("、") { it.label }}。你可以在设置中撤销授权。")
                .setNegativeButton("拒绝") { _, _ -> finish(request, false) }
                .setPositiveButton("允许") { _, _ -> requestAndroid(request) }
                .setOnCancelListener { finish(request, false) }
                .show()
        }
    }

    private fun requestAndroid(request: SiteRequest) {
        if (pending !== request || !isCurrent(request)) { finish(request, false); return }
        val missing = request.capabilities.filterNot { it.isGranted(activity) }.flatMap { it.permissions }.distinct()
        if (missing.isEmpty()) finish(request, true)
        else {
            systemInFlight = true
            activity.requestPermissions(missing.toTypedArray(), SITE_PERMISSIONS)
        }
    }

    fun onPermissionResult() {
        systemInFlight = false
        pending?.let { finish(it, it.capabilities.all { capability -> capability.isGranted(activity) }) }
    }

    private fun isCurrent(request: SiteRequest) = isActive(request.web) && secureSite(request.web.url) == request.site

    private fun finish(request: SiteRequest, allow: Boolean) {
        if (pending !== request) return
        pending = null
        dialog?.dismiss()
        dialog = null
        val granted = allow && isCurrent(request)
        if (granted) {
            store.remember(request.site, request.capabilities)
            lastGrants = lastGrants + request.capabilities.map { request.site to it }
        }
        request.respond(granted)
    }

    fun cancelFor(web: WebView) {
        file?.takeIf { it.web === web }?.let {
            file = null
            it.callback.onReceiveValue(null)
        }
        pending?.takeIf { it.web === web }?.let { finish(it, false) }
    }

    fun cancelMedia(request: PermissionRequest) {
        if (pending?.media !== request) return
        pending = null
        dialog?.dismiss()
        dialog = null
    }

    fun hideLocation(web: WebView) {
        pending?.takeIf { it.web === web && it.media == null }?.let { finish(it, false) }
    }

    fun revokedSites(): Set<String> {
        val current = store.grants()
        return (lastGrants - current).map { it.first }.toSet().also { lastGrants = current }
    }

    companion object {
        const val CHOOSE_FILES = 703
        const val SITE_PERMISSIONS = 704
    }
}
