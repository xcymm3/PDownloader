package com.pdownloader.browser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import android.widget.ImageView
import androidx.core.content.FileProvider
import com.pdownloader.browser.media.*
import com.pdownloader.browser.download.*
import com.pdownloader.browser.update.AppUpdateController
import com.pdownloader.browser.update.PROJECT_URL
import java.io.File

class MainActivity : ThemedActivity() {
    private val updates by lazy { AppUpdateController(this) }
    private val siteAdapters by lazy { SiteAdapters { android.webkit.CookieManager.getInstance().getCookie(it) } }
    private val shareRecognition by lazy { ShareRecognition(this, ::showSharedResources, ::openSharedPage) }
    private val websiteAccess by lazy {
        WebsiteAccess(this) { web ->
            !isFinishing && !isDestroyed && section == 0 && browser.isShown && tabs.isNotEmpty() && current.web === web
        }
    }
    private data class Tab(
        val id: String = UUID.randomUUID().toString(),
        var url: String = "", var title: String = "新标签页",
        var web: WebView? = null, var state: Bundle? = null,
        var preview: Bitmap? = null,
        var touched: Long = 0, var failed: Boolean = false,
        @Volatile var probeSession: ProbeSession? = null,
        var recovery: DownloadTask? = null
    )
    private val tabs = mutableListOf<Tab>()
    private var selectedId = ""
    private val current: Tab get() = tabs.first { it.id == selectedId }
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var store: BrowserStore
    private lateinit var root: FrameLayout
    private lateinit var chrome: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var homeButton: BrowserIcon
    private var homePage: HomePageView? = null
    private lateinit var history: HistoryView
    private var historyOpen = false
    private lateinit var bookmarks: HistoryView
    private var bookmarksOpen = false
    private lateinit var tabOverview: TabOverviewView
    private var tabsOpen = false
    private var browserMenu: BrowserMenu? = null
    internal val menuContent: View? get() = browserMenu?.content
    private lateinit var browser: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var placeholder: LinearLayout
    private lateinit var address: EditText
    private val suggestions by lazy { AddressSuggestions(this, ::suggestAddresses, ::openSuggestion) }
    private lateinit var progress: ProgressBar
    private lateinit var tabsButton: BrowserIcon
    private lateinit var backButton: BrowserIcon
    private lateinit var forwardButton: BrowserIcon
    private lateinit var refreshButton: BrowserIcon
    private val bottomIcons = mutableListOf<BrowserIcon>()
    private var section = 0
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    private var fullContainer: FrameLayout? = null
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private lateinit var probe: MediaProbe
    private lateinit var mediaImages: MediaImages
    private lateinit var downloads: DownloadsView
    private lateinit var completedDownloads: DownloadsView
    private lateinit var floating: LinearLayout
    private lateinit var resourceFab: BrowserIcon
    private var imageGallery: ImageGalleryDialog? = null
    private var mediaSheet: MediaSheet? = null
    private var exportTaskId: String? = null
    private var pendingStoragePermission: (() -> Unit)? = null
    private val probeUi = android.os.Handler(android.os.Looper.getMainLooper())
    private var probeUpdatePending = false
    private var webSeekPosition: SeekPosition? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportTaskId = savedInstanceState?.getString("exportTask")
        store = BrowserStore(applicationContext)
        mediaImages = MediaImages()
        probe = MediaProbe(this) { onProbeChanged() }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        buildUi()
        applySystemBarAppearance()
        restoreSession()
        showTab(selectedId)
        if (savedInstanceState != null) {
            selectSection(savedInstanceState.getInt("section", 0).coerceIn(0, 2))
            history.query = savedInstanceState.getString("historyQuery").orEmpty()
            homePage?.query = savedInstanceState.getString("homeQuery").orEmpty()
            bookmarks.query = savedInstanceState.getString("bookmarksQuery").orEmpty()
            when {
                savedInstanceState.getBoolean("historyOpen") -> openHistory()
                savedInstanceState.getBoolean("bookmarksOpen") -> openBookmarks()
                savedInstanceState.getBoolean("tabsOpen") -> showTabs()
            }
            completedDownloads.setCategory(runCatching { DownloadCategory.valueOf(savedInstanceState.getString("downloadCategory").orEmpty()) }.getOrDefault(DownloadCategory.VIDEO))
        }
        if (savedInstanceState == null) handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { goBack() }
        }
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(color(R.color.surface)) }
        chrome = column()
        root.addView(chrome, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (fullView == null) {
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    val keyboard = insets.getInsets(WindowInsets.Type.ime())
                    chrome.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
                } else {
                    @Suppress("DEPRECATION")
                    chrome.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
            }
            insets
        }
        browser = column()
        chrome.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        toolbar = row().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        homeButton = icon("home", "打开新标签页首页") { navigateHome() }
        toolbar.addView(homeButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        address = EditText(this).apply {
            contentDescription = "地址栏"
            hint = "搜索或输入网址"; textSize = 15f; isSingleLine = true
            setTextColor(color(R.color.ink)); setHintTextColor(color(R.color.muted))
            background = shape(R.color.surface_secondary, 28)
            setPadding(dp(16), 0, dp(12), 0)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_GO || (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_UP)) {
                    submitAddress(); true
                } else false
            }
        }
        toolbar.addView(address, LinearLayout.LayoutParams(0, dp(48), 1f))
        tabsButton = icon("tabs", "管理标签页") { showTabs() }
        toolbar.addView(tabsButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        val menu = icon("menu", "浏览器菜单") { showMenu(null) }
        menu.setOnClickListener { showMenu(menu) }
        toolbar.addView(menu, LinearLayout.LayoutParams(dp(48), dp(48)))
        browser.addView(toolbar)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = android.content.res.ColorStateList.valueOf(color(R.color.accent)); visibility = View.INVISIBLE
        }
        browser.addView(progress, LinearLayout.LayoutParams(-1, dp(2)))
        val webArea = FrameLayout(this)
        content = FrameLayout(this)
        webArea.addView(content, FrameLayout.LayoutParams(-1, -1))
        floating = column().apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
        resourceFab = BrowserIcon(this, "resources", "本页资源") { showMedia(false) }.apply {
            foregroundColor = color(R.color.on_accent)
            glyphSizeDp = 24
            background = shape(R.color.accent, 24)
            elevation = dp(3).toFloat()
        }
        floating.addView(resourceFab, LinearLayout.LayoutParams(dp(48), dp(48)))
        webArea.addView(floating, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(8), dp(8), dp(12), dp(16)) })
        configureFloating(webArea)
        webArea.addView(suggestions.bind(address), FrameLayout.LayoutParams(-1, 0, Gravity.TOP).apply {
            setMargins(dp(12), dp(4), dp(12), 0)
        })
        browser.addView(webArea, LinearLayout.LayoutParams(-1, 0, 1f))
        // Navigation controls live in the menu. Keep state holders for menu enablement.
        backButton = icon("back", "后退") { goBack() }
        forwardButton = icon("forward", "前进") { current.web?.goForward() }
        refreshButton = icon("reload", "刷新网页") { current.failed = false; current.web?.reload() }
        placeholder = column().apply { visibility = View.GONE }
        fun downloadPage(completed: Boolean) = DownloadsView(this, mediaImages,
            source = { url -> selectSection(0); load(url) },
            recover = ::openRecovery,
            online = { task -> previewTask(task.copy(state = TaskState.QUEUED)) },
            open = ::openDownloaded, export = ::exportDownloaded, share = ::shareDownloaded, start = ::startDownloads,
            completedPage = completed)
        downloads = downloadPage(false)
        completedDownloads = downloadPage(true).apply { visibility = View.GONE }
        placeholder.addView(downloads, LinearLayout.LayoutParams(-1, 0, 1f))
        placeholder.addView(completedDownloads, LinearLayout.LayoutParams(-1, 0, 1f))
        chrome.addView(placeholder, LinearLayout.LayoutParams(-1, 0, 1f))
        history = HistoryView(this, store, io, back = ::closeHistory, open = { url -> closeHistory(); load(url) }).apply { visibility = View.GONE }
        bookmarks = HistoryView(this, store, io, back = ::closeBookmarks, open = { url -> closeBookmarks(); load(url) }, table = "bookmarks").apply { visibility = View.GONE }
        tabOverview = TabOverviewView(this, back = ::closeTabs, add = { selectSection(0); newTab() },
            select = ::selectTab, close = ::closeTab).apply { visibility = View.GONE }
        chrome.addView(history, LinearLayout.LayoutParams(-1, 0, 1f))
        chrome.addView(bookmarks, LinearLayout.LayoutParams(-1, 0, 1f))
        chrome.addView(tabOverview, LinearLayout.LayoutParams(-1, 0, 1f))
        chrome.addView(View(this).apply { setBackgroundColor(color(R.color.divider)) }, LinearLayout.LayoutParams(-1, dp(1)))
        val nav = row()
        listOf(Triple("home", "浏览", 0), Triple("download", "下载中", 1), Triple("done", "已下载", 2)).forEach { (kind, title, index) ->
            val image = icon(kind, title) { selectSection(index) }
            bottomIcons.add(image)
            nav.addView(image, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        chrome.addView(nav)
        selectSection(0)
    }

    private fun icon(kind: String, description: String, action: () -> Unit) = BrowserIcon(this, kind, description, action)

    fun selectSection(index: Int) {
        browserMenu?.dismiss()
        if (index != section) hideKeyboard()
        if (bookmarksOpen) { bookmarksOpen = false; bookmarks.hide(); hideKeyboard() }
        if (tabsOpen) { tabsOpen = false; tabOverview.visibility = View.GONE }
        if (historyOpen) { historyOpen = false; history.hide(); hideKeyboard() }
        if (fullView != null) exitFullscreen()
        section = index
        browser.visibility = if (index == 0) View.VISIBLE else View.GONE
        placeholder.visibility = if (index == 0) View.GONE else View.VISIBLE
        bottomIcons.forEachIndexed { i, icon -> icon.selectedIcon = i == index; icon.isSelected = i == index }
        if (tabs.isNotEmpty()) {
            if (index == 0) current.web?.onResume() else current.web?.onPause()
        }
        downloads.visibility = if (index == 1) View.VISIBLE else View.GONE
        completedDownloads.visibility = if (index == 2) View.VISIBLE else View.GONE
        if (index == 1) { downloads.show(false); completedDownloads.hide() }
        else if (index == 2) { completedDownloads.show(true); downloads.hide() }
        else { downloads.hide(); completedDownloads.hide() }
        if (index != 0) probe.pause()
        else { if (tabs.isNotEmpty()) probe.attach(current.probeSession, current.web); probe.resume() }
    }

    private fun submitAddress() {
        val url = AddressResolver.resolve(address.text.toString())
        if (url == null) { address.error = "请输入有效的网址或搜索内容"; return }
        hideKeyboard()
        load(url)
    }

    private fun suggestAddresses(query: String, deliver: (List<AddressSuggestion>) -> Unit) {
        val openTabs = tabs.asSequence().filter { AddressResolver.isWebUrl(it.url) &&
            (it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true)) }
            .sortedByDescending { it.touched }
            .map { AddressSuggestion(it.title, it.url, "切换标签", it.id) }
            .toList()
        io.execute {
            val saved = runCatching { store.suggest(query) }.getOrDefault(emptyList())
            runOnUiThread { if (!isDestroyed) deliver((openTabs + saved).distinctBy { it.url }.take(8)) }
        }
    }

    private fun openSuggestion(suggestion: AddressSuggestion) {
        hideKeyboard()
        val tab = tabs.find { it.id == suggestion.tabId }
        if (tab != null) showTab(tab.id) else load(suggestion.url)
    }

    private fun hideKeyboard() {
        currentFocus?.clearFocus()
        address.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
    }

    private fun load(url: String) {
        if (!AddressResolver.isWebUrl(url)) return
        val existing = current.web
        current.url = url; current.failed = false
        showTab(selectedId)
        existing?.loadUrl(url)
        persistSession()
    }

    fun navigateHome() {
        selectSection(0)
        hideKeyboard()
        exitFullscreen()
        release(current, keepState = false)
        current.url = ""; current.title = "新标签页"; current.failed = false
        showTab(selectedId); persistSession()
    }

    fun openNewTab(url: String = "") { newTab(url) }
    private fun newTab(url: String = ""): Tab? {
        if (tabs.size >= 32) { toast("最多打开 32 个标签页，请先关闭部分标签"); return null }
        val tab = Tab(url = url)
        tabs.add(tab); showTab(tab.id); persistSession()
        return tab
    }

    private fun showTab(id: String) {
        if (fullView != null) exitFullscreen()
        if (selectedId != id) { mediaSheet?.dismiss(); mediaSheet = null }
        if (id != selectedId) captureTabPreview()
        tabs.find { it.id == selectedId }?.web?.let {
            if (id != selectedId) websiteAccess.cancelFor(it)
            it.onPause()
        }
        selectedId = id
        current.touched = System.nanoTime()
        homePage = null
        content.removeAllViews()
        if (current.url.isEmpty()) showHome() else {
            val web = current.web ?: createWebView(current).also { view ->
                current.web = view
                val restored = current.state?.let { view.restoreState(it) }
                current.state = null
                if (restored == null) view.loadUrl(current.url)
            }
            (web.parent as? ViewGroup)?.removeView(web)
            content.addView(web, FrameLayout.LayoutParams(-1, -1))
            if (section == 0) web.onResume() else web.onPause()
            if (current.failed) showError("网页未能加载", "请检查网络连接，或稍后重试。")
        }
        tabs.filter { it.id != id && it.web != null }.sortedByDescending { it.touched }.drop(3).forEach { release(it, true) }
        probe.attach(current.probeSession, current.web)
        updateToolbar(); persistSession()
    }

    private fun showHome() {
        homePage = HomePageView(this, navigate = { url -> hideKeyboard(); load(url) },
            bookmarks = { showRecords("bookmarks") }, history = ::openHistory, suggestions = suggestions::bind).also {
            content.addView(it, FrameLayout.LayoutParams(-1, -1))
        }
    }

    fun openHistory() {
        mediaSheet?.dismiss(); imageGallery?.dismiss()
        hideKeyboard(); selectSection(0)
        historyOpen = true
        browser.visibility = View.GONE
        current.web?.onPause(); probe.pause()
        history.show()
    }

    fun closeHistory() { if (historyOpen) selectSection(0) }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: Tab): WebView = WebView(this).apply {
        setBackgroundColor(color(R.color.surface))
        settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            useWideViewPort = true; loadWithOverviewMode = true
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = false
            safeBrowsingEnabled = true
            if (Build.VERSION.SDK_INT >= 33) isAlgorithmicDarkeningAllowed =
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (AddressResolver.isWebUrl(url)) return false
                if (request.isForMainFrame && request.hasGesture()) offerExternal(request.url)
                return true
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (tab.web !== view) return
                websiteAccess.cancelFor(view)
                tab.url = url; tab.failed = false
                beginProbe(tab, view, url)
                if (tab.id == selectedId) { clearError(); updateToolbar() }
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (tab.web !== view) return
                tab.url = url; tab.title = view.title?.takeIf { it.isNotBlank() } ?: url
                val title = tab.title
                if (!tab.failed && AddressResolver.isWebUrl(url)) {
                    val favicon = view.favicon
                    io.execute {
                        store.save("history", title, url)
                        favicon?.let { runCatching { store.saveIcon(url, it) } }
                    }
                }
                if (tab.id == selectedId) updateToolbar()
                persistSession()
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (tab.web === view && AddressResolver.isWebUrl(url) && tab.probeSession?.pageUrl != url) {
                    tab.url = url; beginProbe(tab, view, url)
                    if (tab.id == selectedId) updateToolbar()
                }
            }
            override fun onLoadResource(view: WebView, url: String) {
                if (tab.web === view) probe.observeUrl(tab.probeSession, url)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && tab.web === view) {
                    tab.failed = true
                    if (tab.id == selectedId) showError("网页未能加载", "请检查网址和网络连接，然后重试。")
                }
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (tab.web === view) {
                    if (tab.id == selectedId) exitFullscreen()
                    release(tab, false); tab.failed = true
                    if (tab.id == selectedId) showError("网页进程已停止", "点击重试，重新打开这个标签页。")
                }
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                probe.observe(tab.probeSession, request)
                if (BuildConfig.DEBUG && request.url.toString() == "https://pdownloader.test/video") {
                    return runCatching { WebResourceResponse("text/html", "utf-8", assets.open("video-test.html")) }.getOrNull()
                }
                return null
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
                websiteAccess.chooseFiles(view, callback, params)

            override fun onPermissionRequest(request: PermissionRequest) =
                websiteAccess.requestMedia(this@apply, request)

            override fun onPermissionRequestCanceled(request: PermissionRequest) = websiteAccess.cancelMedia(request)

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                websiteAccess.askLocation(this@apply, origin) { allowed -> callback.invoke(origin, allowed, false) }
            }

            override fun onGeolocationPermissionsHidePrompt() = websiteAccess.hideLocation(this@apply)

            override fun onReceivedIcon(view: WebView, icon: Bitmap) {
                val url = view.url ?: return
                if (tab.web === view && !tab.failed && AddressResolver.isWebUrl(url)) {
                    io.execute { runCatching { store.saveIcon(url, icon) } }
                }
            }
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (tab.id == selectedId && tab.web === view) {
                    this@MainActivity.progress.progress = newProgress
                    this@MainActivity.progress.visibility = if (newProgress < 100) View.VISIBLE else View.INVISIBLE
                }
            }
            override fun onReceivedTitle(view: WebView, title: String?) {
                if (tab.web === view && !title.isNullOrBlank()) {
                    tab.title = title
                    tab.probeSession?.let { session ->
                        val previous = session.title
                        session.title = title.take(200)
                        session.groups.values.filter { it.title == previous }.forEach { it.title = session.title }
                    }
                    onProbeChanged(); persistSession()
                }
            }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture || tab.id != selectedId) return false
                val popup = newTab() ?: return false
                val target = createWebView(popup)
                popup.web = target
                (resultMsg.obj as WebView.WebViewTransport).webView = target
                resultMsg.sendToTarget()
                // The popup URL arrives after the transport has been attached.
                content.removeAllViews(); content.addView(target, FrameLayout.LayoutParams(-1, -1))
                return true
            }
            override fun onCloseWindow(window: WebView) { tabs.find { it.web === window }?.let { closeTab(it.id) } }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (tab.id != selectedId || fullView != null) { callback.onCustomViewHidden(); return }
                enterFullscreen(view, callback)
            }
            override fun onHideCustomView() { exitFullscreen() }
        }
        setDownloadListener { url, userAgent, disposition, mime, length ->
            if (!webUrl(url)) {
                if (tab.id == selectedId) toast("此下载地址暂不支持")
                return@setDownloadListener
            }
            val type = mime.orEmpty()
            val fileName = URLUtil.guessFileName(url, disposition, type)
            val kind = downloadKind(url, type, fileName)
            if (kind == MediaKind.OTHER) {
                if (tab.id == selectedId && section == 0 && browser.isShown) {
                    offerFileDownload(url, type, length, fileName, RequestContext(tab.url, userAgent ?: settings.userAgentString))
                }
            } else {
                val session = tab.probeSession ?: probe.begin(tab.id, tab.url, settings.userAgentString, tab.title).also { tab.probeSession = it }
                probe.direct(session, url, type, length, kind)
                if (tab.id == selectedId && section == 0 && browser.isShown) showMedia(kind == MediaKind.IMAGE)
            }
        }
    }

    private fun offerFileDownload(url: String, mime: String, length: Long, fileName: String, context: RequestContext) {
        val title = otherFileName(fileName)
        val file = MediaVariant(url, MediaKind.OTHER, size = length, mime = mime)
        val group = MediaGroup(stableId(url), title, context = context)
        val recovery = recoveryFor(context.pageUrl)?.takeIf { it.variant.kind == MediaKind.OTHER }
        if (recovery != null) {
            confirmRecovery(recovery, group, file)
            return
        }
        AlertDialog.Builder(this).setTitle("下载文件？").setMessage("$title\n${bytes(length)}")
            .setNegativeButton("取消", null)
            .setPositiveButton("下载") { _, _ -> submitDownloads(listOf(group to file)) }
            .show()
    }

    private fun offerExternal(uri: Uri) {
        if (uri.scheme !in setOf("mailto", "tel", "sms", "geo", "intent")) { toast("暂不支持此链接类型"); return }
        AlertDialog.Builder(this).setTitle("在其他应用中打开？").setMessage(uri.toString().take(200))
            .setNegativeButton("取消", null).setPositiveButton("打开") { _, _ ->
                val external = if (uri.scheme == "intent") runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()
                    else Intent(Intent.ACTION_VIEW, uri)
                external?.apply {
                    component = null; selector = null
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    flags = 0
                    // Only a fresh VIEW intent is launched; arbitrary extras/actions are discarded.
                    val safe = Intent(Intent.ACTION_VIEW, data).addCategory(Intent.CATEGORY_BROWSABLE)
                    if (safe.data?.scheme in setOf("file", "content", "javascript", "data", null)) { toast("无法打开此链接"); return@apply }
                    runCatching { startActivity(safe) }.onFailure { toast("没有可以打开此链接的应用") }
                }
            }.show()
    }

    private fun updateToolbar() {
        val home = current.url.isEmpty()
        homeButton.visibility = if (home) View.GONE else View.VISIBLE
        address.visibility = if (home) View.GONE else View.VISIBLE
        if (!address.hasFocus()) address.setText(current.url)
        tabsButton.counter = tabs.size; tabsButton.contentDescription = "管理标签页，共 ${tabs.size} 个"
        backButton.isEnabled = current.web?.canGoBack() == true || current.url.isNotEmpty()
        forwardButton.isEnabled = current.web?.canGoForward() == true
        refreshButton.isEnabled = current.url.isNotEmpty()
        progress.progress = current.web?.progress ?: 100
        progress.visibility = if (home) View.GONE else if (progress.progress < 100 && !current.failed) View.VISIBLE else View.INVISIBLE
    }

    fun showMenu(anchor: View? = null) {
        browserMenu?.dismiss()
        val web = current.web
        val hasPage = current.url.isNotEmpty()
        browserMenu = BrowserMenu(this,
            listOf(
                MenuAction("back", "后退", backButton.isEnabled) { goBack() },
                MenuAction("forward", "前进", forwardButton.isEnabled) { web?.goForward() },
                MenuAction("star", "收藏当前网页", hasPage) { bookmark() },
                MenuAction("share", "分享网页", hasPage) { sharePage() },
                MenuAction("reload", "刷新网页", hasPage) { current.failed = false; web?.reload() }
            ), listOf(
                MenuAction("plus", "新建标签页") { newTab() },
                MenuAction("history", "历史记录") { openHistory() },
                MenuAction("star", "收藏夹") { openBookmarks() },
                MenuAction("settings", "设置") { startActivity(Intent(this, SettingsActivity::class.java)) },
                MenuAction("info", "关于") { showAbout() }
            ))
        browserMenu?.show(anchor ?: toolbar.getChildAt(toolbar.childCount - 1))
    }
    private fun sharePage() {
        if (current.url.isNotEmpty()) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, current.url)
        }, "分享网页"))
    }
    private fun showAbout() {
        val project = PROJECT_URL
        AlertDialog.Builder(this).setTitle("关于 PDownloader")
            .setMessage("版本 ${BuildConfig.VERSION_NAME}\n作者 xcymm3\n\n$project")
            .setNegativeButton("关闭", null)
            .setPositiveButton("打开 GitHub") { _, _ -> load(project) }.show()
    }

    private fun bookmark() {
        if (!AddressResolver.isWebUrl(current.url)) { toast("请先打开要收藏的网页"); return }
        val title = current.title; val url = current.url
        io.execute { store.save("bookmarks", title, url); runOnUiThread { if (!isDestroyed) toast("已添加到收藏夹") } }
    }

    fun showTabs() {
        captureTabPreview(); selectSection(0); hideKeyboard()
        tabsOpen = true; browser.visibility = View.GONE; tabOverview.visibility = View.VISIBLE
        current.web?.onPause(); probe.pause(); renderTabs()
    }
    fun closeTabs() { if (tabsOpen) selectSection(0) }
    private fun renderTabs() { tabOverview.submit(tabs.map { TabCard(it.id, it.title, it.url, it.preview, it.id == selectedId) }) }
    private fun captureTabPreview() {
        if (tabs.isEmpty() || section != 0 || historyOpen || bookmarksOpen || tabsOpen || content.width <= 0 || content.height <= 0) return
        val child = content.getChildAt(0) ?: return
        if (!child.isLaidOut || child.isLayoutRequested || content.isLayoutRequested) return
        runCatching {
            val width = 256
            val height = minOf(400, (content.height.toFloat() / content.width * width).toInt()).coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(color(R.color.surface))
            canvas.scale(width.toFloat() / content.width, width.toFloat() / content.width)
            content.draw(canvas)
            current.preview = bitmap
        }
    }

    fun selectTab(id: String) { if (tabs.any { it.id == id }) { selectSection(0); showTab(id) } }
    fun closeTab(id: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        exitFullscreen()
        release(tabs[index], false); tabs.removeAt(index)
        if (tabs.isEmpty()) tabs.add(Tab())
        if (id == selectedId) selectedId = tabs[index.coerceAtMost(tabs.lastIndex)].id
        showTab(selectedId)
        if (tabsOpen) renderTabs()
    }

    private fun showRecords(table: String) { if (table == "history") openHistory() else openBookmarks() }
    fun openBookmarks() {
        mediaSheet?.dismiss(); imageGallery?.dismiss(); selectSection(0); hideKeyboard()
        bookmarksOpen = true; browser.visibility = View.GONE
        current.web?.onPause(); probe.pause(); bookmarks.show()
    }
    fun closeBookmarks() { if (bookmarksOpen) selectSection(0) }

    private fun showError(title: String, message: String) {
        clearError()
        val error = column().apply {
            tag = "error"; gravity = Gravity.CENTER; setBackgroundColor(color(R.color.surface)); setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(label(title, 22f)); addView(label(message, 14f, true).apply { setPadding(0, dp(16), 0, dp(24)) })
            addView(action("重试") {
                val url = current.url; current.failed = false
                release(current, false); showTab(selectedId)
                if (url.isEmpty()) navigateHome()
            })
        }
        content.addView(error, FrameLayout.LayoutParams(-1, -1))
        progress.visibility = View.INVISIBLE
    }
    private fun clearError() { content.findViewWithTag<View>("error")?.let { content.removeView(it) } }

    // Product requirement: video fullscreen explicitly uses landscape even with system rotation lock, restored on exit.
    @SuppressLint("SourceLockedOrientationActivity")
    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        hideKeyboard()
        previousOrientation = requestedOrientation
        fullView = view; fullCallback = callback
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        chrome.visibility = View.GONE
        val overlay = SeekGestureFrame(this, read = { webSeekPosition }, commit = { target ->
            current.web?.evaluateJavascript(videoScript("if(Number.isFinite(v.duration)) v.currentTime=Math.min(v.duration,${target}/1000); return null;"), null)
        }).apply { setBackgroundColor(color(R.color.video_background)) }
        fullContainer = overlay
        overlay.addView(view, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        overlay.installHint()
        pollVideoPosition(view)
        overlay.addView(BrowserIcon(this, "collapse", "退出全屏") { exitFullscreen() }.apply {
            foregroundColor = color(R.color.video_foreground); background = shape(R.color.gallery_controls, 24)
        }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { topMargin = dp(32); marginEnd = dp(12) })
        overlay.excluded = { x, y ->
            val close = overlay.getChildAt(overlay.childCount - 1)
            val rect = android.graphics.Rect()
            val bounds = IntArray(2); overlay.getLocationOnScreen(bounds)
            y >= bounds[1] + overlay.height - dp(64) || (close.getGlobalVisibleRect(rect) && rect.contains(x.toInt(), y.toInt()))
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun videoScript(action: String): String = """
        (()=>{const found=[];function scan(d){try{const f=d.fullscreenElement;if(f&&f.tagName==='VIDEO')found.unshift(f);d.querySelectorAll('video').forEach(v=>found.push(v));d.querySelectorAll('iframe').forEach(f=>{try{if(f.contentDocument)scan(f.contentDocument)}catch(e){}})}catch(e){}}scan(document);const v=found.find(v=>!v.paused&&Number.isFinite(v.duration))||found.find(v=>Number.isFinite(v.duration));if(!v)return null;$action})()
    """.trimIndent()
    private fun pollVideoPosition(owner: View) {
        if (fullView !== owner) return
        current.web?.evaluateJavascript(videoScript("return [v.currentTime*1000,v.duration*1000];")) { value ->
            if (fullView === owner) webSeekPosition = runCatching {
                val values = JSONArray(value); SeekPosition(values.getDouble(0).toLong(), values.getDouble(1).toLong())
            }.getOrNull()
        }
        probeUi.postDelayed({ if (fullView === owner) pollVideoPosition(owner) }, 500)
    }

    private fun exitFullscreen() {
        if (fullView == null) return
        val callback = fullCallback
        fullView = null; fullCallback = null; webSeekPosition = null
        fullContainer?.removeAllViews(); root.removeView(fullContainer); fullContainer = null
        chrome.visibility = View.VISIBLE
        requestedOrientation = previousOrientation
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.show(WindowInsets.Type.systemBars()) else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        root.requestApplyInsets()
        applySystemBarAppearance()
        callback?.onCustomViewHidden()
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarAppearance() {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= 30) {
            val appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else appearance, appearance)
        } else if (Build.VERSION.SDK_INT >= 27) {
            window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    private fun release(tab: Tab, keepState: Boolean) {
        probe.retire(tab.probeSession)
        tab.probeSession = null
        val view = tab.web ?: return
        websiteAccess.cancelFor(view)
        tab.state = if (keepState) Bundle().also { view.saveState(it) } else null
        tab.web = null
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.onPause()
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        view.destroy()
    }

    private fun restoreSession() {
        val preferences = getSharedPreferences("session", MODE_PRIVATE)
        val pending = mutableListOf<Pair<Tab, String>>()
        runCatching {
            val values = JSONArray(preferences.getString("tabs", "[]"))
            for (i in 0 until minOf(values.length(), 32)) {
                val value = values.getJSONObject(i)
                val url = value.optString("url").takeIf { it.isEmpty() || AddressResolver.isWebUrl(it) } ?: ""
                val id = value.optString("id").takeIf { it.isNotBlank() && tabs.none { tab -> tab.id == it } } ?: UUID.randomUUID().toString()
                val tab = Tab(id, url, value.optString("title", "新标签页"))
                tabs.add(tab)
                value.optString("recovery").takeIf(String::isNotBlank)?.let { pending.add(tab to it) }
            }
        }
        if (tabs.isEmpty()) tabs.add(Tab())
        selectedId = preferences.getString("selected", null)?.takeIf { id -> tabs.any { it.id == id } } ?: tabs.first().id
        if (pending.isNotEmpty()) io.execute {
            val repository = DownloadRepository.get(this)
            val tasks = pending.mapNotNull { (tab, id) ->
                repository.get(id)?.takeIf { it.state == TaskState.FAILED }?.let { tab to it }
            }
            runOnUiThread {
                if (!isDestroyed) {
                    tasks.forEach { (tab, task) ->
                        if (tabs.any { it === tab } && tab.recovery == null) tab.recovery = task
                    }
                    persistSession()
                }
            }
        }
    }
    private fun persistSession() {
        if (tabs.isEmpty()) return
        val data = JSONArray()
        tabs.forEach { data.put(JSONObject().put("id", it.id).put("url", it.url).put("title", it.title).put("recovery", it.recovery?.id)) }
        getSharedPreferences("session", MODE_PRIVATE).edit().putString("tabs", data.toString()).putString("selected", selectedId).apply()
    }

    private fun goBack() {
        when {
            mediaSheet?.isShowing() == true -> mediaSheet?.dismiss()
            fullView != null -> exitFullscreen()
            historyOpen -> closeHistory()
            bookmarksOpen -> closeBookmarks()
            tabsOpen -> closeTabs()
            section != 0 -> selectSection(0)
            address.hasFocus() || homePage?.searchFocused == true -> hideKeyboard()
            current.web?.canGoBack() == true -> current.web?.goBack()
            current.url.isNotEmpty() -> navigateHome()
            else -> { persistSession(); moveTaskToBack(true) }
        }
    }
    @Deprecated("Handled by OnBackInvokedDispatcher on Android 13+")
    override fun onBackPressed() { goBack() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    private fun handleIntent(intent: Intent) {
        shareRecognition.cancel()
        if (intent.action == Intent.ACTION_VIEW) intent.dataString?.takeIf(AddressResolver::isWebUrl)?.let { selectSection(0); newTab(it) }
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().take(8192)
            Regex("https?://[^\\s<>“”\"，。！？]+", RegexOption.IGNORE_CASE).find(text)?.value?.takeIf(AddressResolver::isWebUrl)?.let(::recognizeShare)
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("local_download_fixture", false)) DebugFixtures.url()?.let { selectSection(0); newTab(it) }
        if (intent.hasExtra("section")) selectSection(intent.getIntExtra("section", 0).coerceIn(0, 2))
    }
    override fun onPause() {
        shareRecognition.cancel()
        updates.pause()
        probe.pause()
        exitFullscreen(); tabs.forEach { it.web?.onPause() }; persistSession(); super.onPause()
    }
    override fun onResume() {
        super.onResume()
        val revoked = websiteAccess.revokedSites()
        val affected = tabs.filter { it.web != null && secureSite(it.url) in revoked }
        affected.forEach { release(it, true) }
        if (affected.any { it.id == selectedId }) showTab(selectedId)
        downloads.refreshDownloadLimit()
        if (tabs.isNotEmpty() && section == 0 && !historyOpen && !bookmarksOpen && !tabsOpen) { current.web?.onResume(); probe.resume() }
        updates.resume(automatic = true)
    }
    override fun onDestroy() {
        shareRecognition.close()
        browserMenu?.dismiss(); bookmarks.close(); history.close(); completedDownloads.close(); imageGallery?.dismiss(); mediaSheet?.dismiss(); downloads.close(); mediaImages.close()
        exitFullscreen(); tabs.forEach { release(it, false) }; probe.close()
        io.execute { store.close() }; io.shutdown(); super.onDestroy()
    }

    private fun recognizeShare(url: String) {
        val adapter = siteAdapters.match(url)
        if (adapter == null) openSharedPage(url, null)
        else {
            mediaSheet?.dismiss()
            shareRecognition.start(url, android.webkit.WebSettings.getDefaultUserAgent(this), adapter)
        }
    }

    private fun openSharedPage(url: String, message: String?) {
        selectSection(0)
        if (tabs.size < 32) newTab(url) else load(url)
        if (message != null) toast("$message，已打开原网页")
    }

    private fun showSharedResources(originalUrl: String, ua: String, result: ShareMedia.Found) {
        selectSection(0)
        hideKeyboard()
        mediaSheet?.dismiss()
        val session = ProbeSession("share", result.page, ua, result.title)
        result.groups.forEach { session.groups[it.id] = it }
        showResourceSheet(session, false, null, retry = { openSharedPage(originalUrl, null) }, shared = true)
    }

    private fun beginProbe(tab: Tab, web: WebView, url: String) {
        probe.retire(tab.probeSession)
        tab.probeSession = if (webUrl(url)) probe.begin(tab.id, url, web.settings.userAgentString, tab.title) else null
        if (tab.id == selectedId) {
            mediaSheet?.dismiss(); mediaSheet = null
            probe.attach(tab.probeSession, web)
        }
    }

    private fun onProbeChanged() {
        if (probeUpdatePending || isDestroyed) return
        probeUpdatePending = true
        probeUi.postDelayed({
            probeUpdatePending = false
            if (isDestroyed || tabs.isEmpty()) return@postDelayed
            val session = current.probeSession
            val videos = session?.groups?.values?.count { !it.image } ?: 0
            val images = session?.groups?.values?.count { it.image } ?: 0
            floating.visibility = if (current.url.isEmpty() || current.failed) View.GONE else View.VISIBLE
            resourceFab.badgeCount = videos + images
            resourceFab.contentDescription = "本页资源，视频 $videos 项，图片 $images 项"
            resourceFab.tooltipText = resourceFab.contentDescription
            resourceFab.alpha = if (videos + images > 0) 1f else .65f
            if (Build.VERSION.SDK_INT >= 30) resourceFab.stateDescription = if ((session?.pending ?: 0) > 0) "检测中" else "共 ${videos + images} 项"
            mediaSheet?.takeIf { it.isShowing() }?.refresh()
        }, 120)
    }

    private fun showMedia(imagesFirst: Boolean) {
        val session = current.probeSession ?: run { toast("请先打开网页，并尝试播放视频"); return }
        if (mediaSheet?.isShowing() == true) return
        hideKeyboard()
        showResourceSheet(session, imagesFirst, recoveryFor(session.pageUrl), ::rescan)
    }

    private fun showResourceSheet(session: ProbeSession, imagesFirst: Boolean, recovery: DownloadTask?, retry: () -> Unit, shared: Boolean = false) {
        mediaSheet = MediaSheet(this, session, mediaImages, submit = { selected ->
            if (recovery == null) submitDownloads(selected)
            else {
                val item = selected.singleOrNull()?.takeIf { recovery.variant.kind.canRecoverWith(it.second.kind) }
                if (item == null) toast("请选择一个与原任务类型相同的资源")
                else confirmRecovery(recovery, item.first, item.second)
            }
        }, previewVideo = { group, variant ->
            previewTask(DownloadTask(java.util.UUID.randomUUID().toString(), group.title, variant, variant.requestContext ?: group.context))
        }, retry = retry, recoveryTitle = recovery?.title, shared = shared).also { it.show(imagesFirst) }
    }

    private fun openRecovery(task: DownloadTask) {
        val url = task.context.pageUrl
        if (!webUrl(url)) { toast("原网页地址不可用"); return }
        selectSection(0)
        hideKeyboard()
        val previous = tabs.firstOrNull { it.recovery?.id == task.id }
        val tab = if (previous != null) {
            showTab(previous.id)
            if (previous.url != url) load(url) else if (task.failure == DownloadFailure.SOURCE) current.web?.reload()
            previous
        } else if (tabs.size < 32) newTab(url) ?: return else {
            load(url)
            current
        }
        tab.recovery = task
        persistSession()
        toast(if (task.failure == DownloadFailure.LOGIN) "登录后，在原网页打开资源面板恢复任务"
            else "打开原资源后，选择右下角资源图标恢复任务")
    }

    private fun recoveryFor(page: String): DownloadTask? = current.recovery?.takeIf { task ->
        val original = task.context.pageUrl
        val site = origin(page)
        site.isNotEmpty() && (site == origin(original) || sameBilibiliVideoPage(page, original) ||
            original.startsWith("http://", true) && site == origin("https://" + original.substringAfter("://")))
    }

    private fun confirmRecovery(task: DownloadTask, group: MediaGroup, variant: MediaVariant) {
        val context = variant.requestContext ?: group.context
        val message = if (variant.url == task.variant.url && variant.kind == task.variant.kind) "会校验原断点或分片，再继续「${task.title}」。"
            else "将用选中的资源继续原任务「${task.title}」。地址或格式已变化，下载会从头开始。"
        AlertDialog.Builder(this).setTitle("恢复原下载任务？").setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复") { _, _ ->
                io.execute {
                    val restored = runCatching { DownloadRepository.get(this).recover(task.id, variant, context) }.getOrDefault(false)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        if (restored) {
                            tabs.filter { it.recovery?.id == task.id }.forEach { it.recovery = null }
                            persistSession()
                            selectSection(1)
                            startDownloads()
                        } else toast("任务状态已变化，或该资源已有下载任务，请查看下载列表")
                    }
                }
            }.show()
    }

    private fun submitDownloads(snapshot: List<Pair<MediaGroup, MediaVariant>>) {
        val settings = DownloadSettings(this)
        val saveToPublic = settings.saveToPublic
        val add = { requestDownloadStorage(saveToPublic) { enqueueDownloads(snapshot, saveToPublic) } }
        if (settings.warnOnMetered && getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered) {
            AlertDialog.Builder(this).setTitle("使用按流量计费的网络？")
                .setMessage("当前网络可能产生流量费用。")
                .setNegativeButton("取消", null).setPositiveButton("继续下载") { _, _ -> add() }.show()
        } else add()
    }

    @Suppress("DEPRECATION")
    private fun requestDownloadStorage(public: Boolean, add: () -> Unit) {
        val permission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (public && Build.VERSION.SDK_INT < 29 && checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingStoragePermission = add
            requestPermissions(arrayOf(permission), 702)
        } else add()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == WebsiteAccess.SITE_PERMISSIONS) websiteAccess.onPermissionResult()
        if (requestCode == 702) {
            val add = pendingStoragePermission
            pendingStoragePermission = null
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) add?.invoke()
            else toast("公共下载目录需要存储权限，可在设置中改用应用私有目录")
        }
    }

    private fun enqueueDownloads(snapshot: List<Pair<MediaGroup, MediaVariant>>, saveToPublic: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 700)
        }
        io.execute {
            val repository = DownloadRepository.get(this)
            var added = 0
            val result = runCatching {
                snapshot.forEach { (group, variant) ->
                    if (repository.enqueue(group.title, variant, variant.requestContext ?: group.context, group.thumbnail, group.durationMs, saveToPublic).second) added++
                }
            }
            runOnUiThread {
                if (!isDestroyed) {
                    toast(if (result.isFailure) "已添加 $added 项；部分任务创建失败，请检查存储空间后重试" else if (added > 0) "已加入 $added 个下载任务" else "所选资源已有下载任务，请在下载列表查看")
                    if (added > 0) startDownloads()
                }
            }
        }
    }

    private fun rescan() {
        val web = current.web ?: return
        beginProbe(current, web, current.url)
        probe.resume(); showMedia(false)
    }

    // Native views already implement performClick; both tap handlers explicitly invoke it.
    @SuppressLint("ClickableViewAccessibility")
    private fun configureFloating(parent: FrameLayout) {
        val preferences = getSharedPreferences("floating", MODE_PRIVATE)
        var downX = 0f; var downY = 0f; var fromX = 0f; var fromY = 0f; var moved = false
        val touch = View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; fromX = floating.x; fromY = floating.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(8)) moved = true
                    if (moved) {
                        floating.x = (fromX + dx).coerceIn(0f, maxOf(0, parent.width - floating.width).toFloat())
                        floating.y = (fromY + dy).coerceIn(0f, maxOf(0, parent.height - floating.height).toFloat())
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) preferences.edit().putFloat("x", floating.x / maxOf(1, parent.width - floating.width)).putFloat("y", floating.y / maxOf(1, parent.height - floating.height)).apply()
                    else view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        resourceFab.setOnTouchListener(touch)
        parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (preferences.contains("x")) {
                floating.x = preferences.getFloat("x", 1f).coerceIn(0f, 1f) * maxOf(0, parent.width - floating.width)
                floating.y = preferences.getFloat("y", .9f).coerceIn(0f, 1f) * maxOf(0, parent.height - floating.height)
            }
        }
    }

    private fun startDownloads() {
        runCatching { startForegroundService(Intent(this, BrowserDownloadService::class.java)) }.onFailure {
            toast("系统暂不允许后台下载，请保持应用在前台并点击继续")
            io.execute {
                val repository = DownloadRepository.get(this)
                repository.queuedIds().forEach { id -> repository.update(id) { task ->
                    if (task.state == TaskState.QUEUED) task.copy(state = TaskState.PAUSED, error = "系统暂不允许启动下载服务") else task
                } }
            }
        }
    }
    private fun previewTask(task: DownloadTask) {
        if (task.variant.blockedReason.isNotEmpty()) { toast(task.variant.blockedReason); return }
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("preview", DownloadRepository.encode(task).toString()))
    }
    private fun openDownloaded(task: DownloadTask) {
        if (task.variant.kind == MediaKind.OTHER) {
            io.execute {
                val result = runCatching { downloadContentUri(task) }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    result.onSuccess { uri ->
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, downloadMime(task))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            })
                        } catch (_: android.content.ActivityNotFoundException) {
                            toast("没有可打开此文件的应用，可以长按分享或导出")
                        }
                    }.onFailure { toast("本地文件无法读取") }
                }
            }
        } else if (task.variant.kind == MediaKind.IMAGE) {
            io.execute {
                val repository = DownloadRepository.get(this)
                val tasks = repository.all().filter { it.state == TaskState.COMPLETE && it.variant.kind == MediaKind.IMAGE && canRead(repository.playbackUri(it)) }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (tasks.none { it.id == task.id }) { toast("本地文件已不存在"); return@runOnUiThread }
                    hideKeyboard()
                    imageGallery?.dismiss()
                    lateinit var viewer: ImageGalleryDialog
                    viewer = ImageGalleryDialog(this,
                        tasks.map { GalleryPicture(it.id, it.title, uri = repository.playbackUri(it)) },
                        tasks.indexOfFirst { it.id == task.id }, mediaImages,
                        share = { picture -> tasks.find { it.id == picture.id }?.let(::shareDownloaded) },
                        export = { picture -> tasks.find { it.id == picture.id }?.let(::exportDownloaded) },
                        onDismiss = { if (imageGallery === viewer) imageGallery = null })
                    imageGallery = viewer; viewer.show()
                }
            }
        } else startActivity(Intent(this, PlayerActivity::class.java).putExtra("task", task.id))
    }

    private fun shareDownloaded(task: DownloadTask) {
        io.execute {
            val result = runCatching { downloadContentUri(task) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { uri ->
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = downloadMime(task)
                        putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = android.content.ClipData.newRawUri(task.title, uri)
                    }, if (task.variant.kind == MediaKind.HLS) "分享 HLS 离线资源包（ZIP，非 MP4）" else "分享文件"))
                }.onFailure { toast("无法分享文件，请检查本地文件和存储空间") }
            }
        }
    }
    private fun downloadContentUri(task: DownloadTask): Uri = exportSource(task).let { source ->
        if (source.scheme == "file") FileProvider.getUriForFile(this, "$packageName.files", File(requireNotNull(source.path))) else source
    }

    private fun downloadMime(task: DownloadTask): String =
        if (task.variant.kind == MediaKind.HLS) "application/zip"
        else specificMime(task.variant.mime)
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(task.output.substringAfterLast('.', "").lowercase())
            ?: when (task.variant.kind) {
                MediaKind.IMAGE -> "image/*"
                MediaKind.VIDEO -> "video/*"
                else -> "application/octet-stream"
            }
    private fun canRead(uri: Uri): Boolean = runCatching {
        contentResolver.openFileDescriptor(uri, "r")?.use { true } == true
    }.getOrDefault(false)

    private fun exportSource(task: DownloadTask): Uri {
        val repository = DownloadRepository.get(this)
        if (task.variant.kind != MediaKind.HLS) return repository.playbackUri(task).also {
            require(canRead(it)) { "本地文件不存在" }
        }
        task.publicUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }?.takeIf(::canRead)?.let { return it }
        val folder = repository.folder(task.id)
        val archive = File(folder, "offline-package.zip")
        val temporary = File(folder, "export.part")
        try {
            writeOfflineArchive(folder, task.output, temporary)
            require(temporary.renameTo(archive))
            return Uri.fromFile(archive)
        } finally {
            temporary.delete()
        }
    }
    private fun exportDownloaded(task: DownloadTask) {
        exportTaskId = task.id
        val hls = task.variant.kind == MediaKind.HLS
        if (hls) toast("HLS 将导出为离线资源包 ZIP，解压后可播放，非单个 MP4")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = downloadMime(task)
            val name = if (task.variant.kind == MediaKind.OTHER) task.output.ifBlank { otherFileName(task.title) }
                else safeFileName(task.title, if (hls) "zip" else extension(task.variant))
            putExtra(Intent.EXTRA_TITLE, name)
        }, 701)
    }
    @Deprecated("Platform Activity result API retained for the native View shell")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == WebsiteAccess.CHOOSE_FILES) websiteAccess.onFileResult(resultCode, data)
        if (requestCode == 701 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val id = exportTaskId ?: return
            io.execute {
                val result = runCatching {
                    val task = DownloadRepository.get(this).get(id) ?: error("下载记录不存在")
                    val source = exportSource(task)
                    require(source != uri) { "目标与原文件相同" }
                    checkNotNull(contentResolver.openInputStream(source)).use { input ->
                        checkNotNull(contentResolver.openOutputStream(uri, "wt")).use { output -> input.copyTo(output) }
                    }
                }
                runOnUiThread { if (!isDestroyed) toast(if (result.isSuccess) "导出完成，文件已保存到你选择的位置" else "导出失败，请检查目标位置和存储空间") }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("exportTask", exportTaskId)
        outState.putBoolean("bookmarksOpen", bookmarksOpen)
        outState.putString("bookmarksQuery", bookmarks.query)
        outState.putBoolean("tabsOpen", tabsOpen)
        outState.putBoolean("historyOpen", historyOpen)
        outState.putString("historyQuery", history.query)
        outState.putString("homeQuery", homePage?.query)
        outState.putInt("section", section)
        outState.putString("downloadCategory", completedDownloads.selectedCategory.name)
        super.onSaveInstanceState(outState)
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
}
