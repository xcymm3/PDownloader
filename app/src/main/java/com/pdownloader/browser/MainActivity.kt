package com.pdownloader.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
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
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors
import android.widget.ImageView
import androidx.core.content.FileProvider
import com.pdownloader.browser.media.*
import com.pdownloader.browser.download.*
import java.io.File

class MainActivity : Activity() {
    private data class Tab(
        val id: String = UUID.randomUUID().toString(),
        var url: String = "", var title: String = "新标签页",
        var web: WebView? = null, var state: Bundle? = null,
        var touched: Long = 0, var failed: Boolean = false,
        @Volatile var probeSession: ProbeSession? = null
    )
    private val tabs = mutableListOf<Tab>()
    private var selectedId = ""
    private val current: Tab get() = tabs.first { it.id == selectedId }
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var store: BrowserStore
    private lateinit var root: FrameLayout
    private lateinit var chrome: LinearLayout
    private lateinit var browser: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var placeholder: LinearLayout
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var tabsButton: BrowserIcon
    private lateinit var backButton: BrowserIcon
    private lateinit var forwardButton: BrowserIcon
    private lateinit var refreshButton: BrowserIcon
    private val bottomIcons = mutableListOf<BrowserIcon>()
    private val bottomLabels = mutableListOf<TextView>()
    private var section = 0
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    private var fullContainer: FrameLayout? = null
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var generation = 0L
    private var pageDialog: Dialog? = null
    private lateinit var probe: MediaProbe
    private lateinit var mediaImages: MediaImages
    private lateinit var downloads: DownloadsView
    private lateinit var floating: LinearLayout
    private lateinit var resourceFab: BrowserIcon
    private var imageGallery: ImageGalleryDialog? = null
    private var mediaSheet: MediaSheet? = null
    private var exportTaskId: String? = null
    private val probeUi = android.os.Handler(android.os.Looper.getMainLooper())
    private var probeUpdatePending = false

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
            downloads.setCategory(runCatching { DownloadCategory.valueOf(savedInstanceState.getString("downloadCategory").orEmpty()) }.getOrDefault(DownloadCategory.ALL))
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
        val toolbar = row().apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
        toolbar.addView(icon("home", "打开新标签页首页") { navigateHome() }, LinearLayout.LayoutParams(dp(48), dp(48)))
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
        browser.addView(webArea, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = row()
        backButton = icon("back", "后退") { goBack() }
        forwardButton = icon("forward", "前进") { current.web?.goForward() }
        refreshButton = icon("reload", "刷新网页") { current.failed = false; current.web?.reload(); showTab(selectedId) }
        listOf(backButton, forwardButton, refreshButton).forEach { controls.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f)) }
        controls.addView(icon("star", "收藏当前网页") { bookmark() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        browser.addView(controls)
        placeholder = column().apply { visibility = View.GONE }
        downloads = DownloadsView(this, mediaImages,
            source = { url -> selectSection(0); load(url) },
            online = { task -> previewTask(task.copy(state = TaskState.QUEUED)) },
            open = ::openDownloaded, export = ::exportDownloaded, share = ::shareDownloaded, start = ::startDownloads)
        placeholder.addView(downloads, LinearLayout.LayoutParams(-1, -1))
        chrome.addView(placeholder, LinearLayout.LayoutParams(-1, 0, 1f))
        chrome.addView(View(this).apply { setBackgroundColor(color(R.color.divider)) }, LinearLayout.LayoutParams(-1, dp(1)))
        val nav = row()
        listOf(Triple("home", "浏览", 0), Triple("download", "下载中", 1), Triple("done", "已下载", 2)).forEach { (kind, title, index) ->
            val item = column().apply {
                gravity = Gravity.CENTER; isClickable = true; isFocusable = true; contentDescription = title
                setOnClickListener { selectSection(index) }
            }
            val image = icon(kind, title) { selectSection(index) }
            val text = label(title, 12f).apply { gravity = Gravity.CENTER }
            bottomIcons.add(image); bottomLabels.add(text)
            item.addView(image, LinearLayout.LayoutParams(dp(48), dp(36)))
            item.addView(text, LinearLayout.LayoutParams(-1, dp(22)))
            nav.addView(item, LinearLayout.LayoutParams(0, dp(64), 1f))
        }
        chrome.addView(nav)
        selectSection(0)
    }

    private fun icon(kind: String, description: String, action: () -> Unit) = BrowserIcon(this, kind, description, action)

    private fun selectSection(index: Int) {
        if (fullView != null) exitFullscreen()
        section = index
        browser.visibility = if (index == 0) View.VISIBLE else View.GONE
        placeholder.visibility = if (index == 0) View.GONE else View.VISIBLE
        bottomIcons.forEachIndexed { i, icon -> icon.selectedIcon = i == index; icon.isSelected = i == index }
        bottomLabels.forEachIndexed { i, text -> text.setTextColor(color(if (i == index) R.color.accent else R.color.muted)) }
        if (tabs.isNotEmpty()) {
            if (index == 0) current.web?.onResume() else current.web?.onPause()
        }
        if (index != 0) { downloads.show(index == 2); probe.pause() }
        else { downloads.hide(); if (tabs.isNotEmpty()) probe.attach(current.probeSession, current.web); probe.resume() }
    }

    private fun submitAddress() {
        val url = AddressResolver.resolve(address.text.toString())
        if (url == null) { address.error = "请输入有效的网址或搜索内容"; return }
        hideKeyboard()
        load(url)
    }

    private fun hideKeyboard() {
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

    private fun navigateHome() {
        exitFullscreen()
        release(current, keepState = false)
        current.url = ""; current.title = "新标签页"; current.failed = false
        showTab(selectedId); persistSession()
    }

    private fun newTab(url: String = ""): Tab? {
        if (tabs.size >= 32) { toast("最多打开 32 个标签页，请先关闭部分标签"); return null }
        val tab = Tab(url = url)
        tabs.add(tab); showTab(tab.id); persistSession()
        return tab
    }

    private fun showTab(id: String) {
        if (fullView != null) exitFullscreen()
        if (selectedId != id) { mediaSheet?.dismiss(); mediaSheet = null }
        tabs.find { it.id == selectedId }?.web?.onPause()
        selectedId = id
        current.touched = System.nanoTime()
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
        val scroll = ScrollView(this)
        val home = column().apply { setPadding(dp(28), dp(56), dp(28), dp(32)) }
        home.addView(label("PDownloader", 32f).apply { setTextColor(color(R.color.accent)); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) })
        home.addView(label("从这里开始浏览", 16f, true).apply { setPadding(0, dp(12), 0, dp(28)) })
        home.addView(action("搜索或输入网址") {
            address.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(address, InputMethodManager.SHOW_IMPLICIT)
        }.apply { background = shape(R.color.surface_secondary, 28); gravity = Gravity.START or Gravity.CENTER_VERTICAL })
        val shortcuts = row().apply { setPadding(0, dp(28), 0, dp(24)) }
        shortcuts.addView(action("收藏夹") { showRecords("bookmarks") }, LinearLayout.LayoutParams(0, dp(56), 1f))
        shortcuts.addView(action("历史记录") { showRecords("history") }, LinearLayout.LayoutParams(0, dp(56), 1f))
        home.addView(shortcuts)
        home.addView(action("粘贴网页链接") {
            val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
            val text = clip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            address.setText(text); address.requestFocus()
        })
        home.addView(action("视频与图片下载帮助") { showDownloadHelp() })
        val favorites = column()
        home.addView(label("我的收藏", 18f).apply { setPadding(0, dp(24), 0, dp(12)) })
        home.addView(favorites)
        io.execute {
            val records = store.list("bookmarks").take(8)
            runOnUiThread {
                if (isDestroyed || !home.isAttachedToWindow) return@runOnUiThread
                if (records.isEmpty()) favorites.addView(label("还没有收藏，可在网页菜单中添加", 13f, true))
                else records.forEach { record -> favorites.addView(action(record.title) { load(record.url) }) }
            }
        }
        scroll.addView(home); content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }

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
                tab.url = url; tab.failed = false
                beginProbe(tab, view, url)
                if (tab.id == selectedId) { clearError(); updateToolbar() }
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (tab.web !== view) return
                tab.url = url; tab.title = view.title?.takeIf { it.isNotBlank() } ?: url
                val title = tab.title
                if (!tab.failed && AddressResolver.isWebUrl(url)) io.execute { store.save("history", title, url) }
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
        setDownloadListener { url, _, _, mime, length ->
            val session = tab.probeSession ?: probe.begin(tab.id, tab.url, settings.userAgentString, tab.title).also { tab.probeSession = it }
            probe.direct(session, url, mime.orEmpty(), length)
            if (tab.id == selectedId) showMedia(mime?.startsWith("image/") == true)
        }
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
        if (!address.hasFocus()) address.setText(current.url)
        tabsButton.counter = tabs.size; tabsButton.contentDescription = "管理标签页，共 ${tabs.size} 个"
        backButton.isEnabled = current.web?.canGoBack() == true || current.url.isNotEmpty()
        forwardButton.isEnabled = current.web?.canGoForward() == true
        refreshButton.isEnabled = current.url.isNotEmpty()
        progress.progress = current.web?.progress ?: 100
        progress.visibility = if (progress.progress < 100 && !current.failed) View.VISIBLE else View.INVISIBLE
    }

    private fun showMenu(it: View?) {
        val popup = PopupMenu(this, it ?: tabsButton)
        listOf("新建标签页", "收藏当前网页", "收藏夹", "历史记录", "分享网页", "本页视频与图片", "重新检测资源", "下载帮助").forEachIndexed { index, title -> popup.menu.add(0, index, index, title) }
        if (BuildConfig.DEBUG) popup.menu.add(0, 8, 8, "本地下载功能测试")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> newTab()
                1 -> bookmark()
                2 -> showRecords("bookmarks")
                3 -> showRecords("history")
                4 -> if (current.url.isNotEmpty()) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, current.url) }, "分享网页")) else toast("请先打开网页")
                5 -> showMedia(false)
                6 -> rescan()
                7 -> showDownloadHelp()
                8 -> DebugFixtures.url()?.let { load(it) }
            }
            true
        }
        popup.show()
    }

    private fun bookmark() {
        if (!AddressResolver.isWebUrl(current.url)) { toast("请先打开要收藏的网页"); return }
        val title = current.title; val url = current.url
        io.execute { store.save("bookmarks", title, url); runOnUiThread { if (!isDestroyed) toast("已添加到收藏夹") } }
    }

    private fun showTabs() {
        val snapshot = tabs.toList()
        val dialog = panel("标签页 · ${tabs.size}")
        val list = ListView(this)
        list.adapter = object : BaseAdapter() {
            override fun getCount() = snapshot.size
            override fun getItem(position: Int) = snapshot[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val tab = getItem(position)
                return recordRow((if (tab.id == selectedId) "当前 · " else "") + tab.title, tab.url.ifEmpty { "新标签页" }, "关闭标签页", {
                    dialog.dismiss(); showTab(tab.id)
                }, { dialog.dismiss(); closeTab(tab.id); showTabs() })
            }
        }
        val body = dialog.findViewById<LinearLayout>(PANEL_BODY)
        body.addView(action("＋ 新建标签页") { dialog.dismiss(); newTab() })
        body.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.show(); sizePanel(dialog)
    }

    private fun closeTab(id: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        exitFullscreen()
        release(tabs[index], false); tabs.removeAt(index)
        if (tabs.isEmpty()) tabs.add(Tab())
        if (id == selectedId) selectedId = tabs[index.coerceAtMost(tabs.lastIndex)].id
        showTab(selectedId)
    }

    private fun showRecords(table: String) {
        val token = ++generation
        val dialog = panel(if (table == "bookmarks") "收藏夹" else "历史记录")
        val body = dialog.findViewById<LinearLayout>(PANEL_BODY)
        val loading = label("正在读取…", 15f, true).apply { gravity = Gravity.CENTER }
        body.addView(loading, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.show(); sizePanel(dialog)
        io.execute {
            val records = store.list(table)
            runOnUiThread {
                if (isDestroyed || token != generation || !dialog.isShowing) return@runOnUiThread
                body.removeView(loading)
                if (table == "history" && records.isNotEmpty()) body.addView(action("清空历史记录") {
                    AlertDialog.Builder(this).setTitle("清空全部浏览历史？").setMessage("此操作无法撤销，收藏夹将保留。")
                        .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ ->
                            io.execute { store.clearHistory(); runOnUiThread { if (!isDestroyed) { dialog.dismiss(); showRecords(table) } } }
                        }.show()
                })
                if (records.isEmpty()) body.addView(label(if (table == "bookmarks") "还没有收藏的网页" else "还没有浏览记录", 16f, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, 0, 1f))
                else {
                    val list = ListView(this)
                    list.adapter = object : BaseAdapter() {
                        override fun getCount() = records.size
                        override fun getItem(position: Int) = records[position]
                        override fun getItemId(position: Int) = records[position].id
                        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                            val record = getItem(position)
                            val subtitle = if (table == "history") DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.visitedAt)) + "  ·  " + record.url else record.url
                            return recordRow(record.title, subtitle, "删除记录", { dialog.dismiss(); load(record.url) }, {
                                io.execute { store.delete(table, record.id); runOnUiThread { if (!isDestroyed) { dialog.dismiss(); showRecords(table) } } }
                            })
                        }
                    }
                    body.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
                }
            }
        }
    }

    private fun recordRow(title: String, subtitle: String, deleteDescription: String, open: () -> Unit, delete: () -> Unit): View = row().apply {
        setPadding(dp(16), dp(8), dp(4), dp(8))
        val text = column().apply {
            isClickable = true; isFocusable = true; setOnClickListener { open() }
            addView(label(title, 16f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(label(subtitle, 12f, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, dp(6)) })
            minimumHeight = dp(56)
        }
        addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        addView(icon("close", deleteDescription, delete), LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    private fun panel(title: String): Dialog {
        pageDialog?.dismiss()
        return Dialog(this).apply {
            pageDialog = this
            val layout = column().apply { setBackgroundColor(color(R.color.surface)) }
            val heading = row()
            heading.addView(label(title, 21f).apply { setPadding(dp(20), 0, 0, 0) }, LinearLayout.LayoutParams(0, dp(64), 1f))
            heading.addView(icon("close", "关闭面板") { dismiss() }, LinearLayout.LayoutParams(dp(48), dp(48)))
            layout.addView(heading)
            layout.addView(column().apply { id = PANEL_BODY }, LinearLayout.LayoutParams(-1, 0, 1f))
            setContentView(layout)
        }
    }
    private fun sizePanel(dialog: Dialog) {
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(resources.displayMetrics.widthPixels, (resources.displayMetrics.heightPixels * .82).toInt())
            setGravity(Gravity.BOTTOM)
        }
    }

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

    // Product requirement: video fullscreen defaults to portrait, restored on exit.
    @SuppressLint("SourceLockedOrientationActivity")
    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        hideKeyboard()
        previousOrientation = requestedOrientation
        fullView = view; fullCallback = callback
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        chrome.visibility = View.GONE
        val overlay = FrameLayout(this).apply { setBackgroundColor(color(R.color.video_background)) }
        fullContainer = overlay
        overlay.addView(view, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        overlay.addView(BrowserIcon(this, "collapse", "退出全屏") { exitFullscreen() }.apply {
            foregroundColor = color(R.color.video_foreground); background = shape(R.color.gallery_controls, 24)
        }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { topMargin = dp(32); marginEnd = dp(12) })
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

    private fun exitFullscreen() {
        if (fullView == null) return
        val callback = fullCallback
        fullView = null; fullCallback = null
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
        runCatching {
            val values = JSONArray(preferences.getString("tabs", "[]"))
            for (i in 0 until minOf(values.length(), 32)) {
                val value = values.getJSONObject(i)
                val url = value.optString("url").takeIf { it.isEmpty() || AddressResolver.isWebUrl(it) } ?: ""
                val id = value.optString("id").takeIf { it.isNotBlank() && tabs.none { tab -> tab.id == it } } ?: UUID.randomUUID().toString()
                tabs.add(Tab(id, url, value.optString("title", "新标签页")))
            }
        }
        if (tabs.isEmpty()) tabs.add(Tab())
        selectedId = preferences.getString("selected", null)?.takeIf { id -> tabs.any { it.id == id } } ?: tabs.first().id
    }
    private fun persistSession() {
        if (tabs.isEmpty()) return
        val data = JSONArray()
        tabs.forEach { data.put(JSONObject().put("id", it.id).put("url", it.url).put("title", it.title)) }
        getSharedPreferences("session", MODE_PRIVATE).edit().putString("tabs", data.toString()).putString("selected", selectedId).apply()
    }

    private fun goBack() {
        when {
            mediaSheet?.isShowing() == true -> mediaSheet?.dismiss()
            fullView != null -> exitFullscreen()
            section != 0 -> selectSection(0)
            current.web?.canGoBack() == true -> current.web?.goBack()
            current.url.isNotEmpty() -> navigateHome()
            else -> { persistSession(); moveTaskToBack(true) }
        }
    }
    @Deprecated("Handled by OnBackInvokedDispatcher on Android 13+")
    override fun onBackPressed() { goBack() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) intent.dataString?.takeIf(AddressResolver::isWebUrl)?.let { selectSection(0); newTab(it) }
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().take(8192)
            Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).find(text)?.value?.takeIf(AddressResolver::isWebUrl)?.let { selectSection(0); newTab(it) }
        }
        if (intent.hasExtra("section")) selectSection(intent.getIntExtra("section", 0).coerceIn(0, 2))
    }
    override fun onPause() {
        probe.pause()
        exitFullscreen(); tabs.forEach { it.web?.onPause() }; persistSession(); super.onPause()
    }
    override fun onResume() { super.onResume(); if (tabs.isNotEmpty() && section == 0) { current.web?.onResume(); probe.resume() } }
    override fun onDestroy() {
        imageGallery?.dismiss(); mediaSheet?.dismiss(); downloads.close(); mediaImages.close()
        pageDialog?.dismiss(); exitFullscreen(); tabs.forEach { release(it, false) }; probe.close()
        io.execute { store.close() }; io.shutdown(); super.onDestroy()
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
        mediaSheet = MediaSheet(this, session, mediaImages, submit = { snapshot ->
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 700)
            }
            io.execute {
                val repository = DownloadRepository.get(this)
                var added = 0
                val result = runCatching {
                    snapshot.forEach { (group, variant) -> if (repository.enqueue(group.title, variant, variant.requestContext ?: group.context, group.thumbnail, group.durationMs).second) added++ }
                }
                runOnUiThread {
                    if (!isDestroyed) {
                        toast(if (result.isFailure) "已添加 $added 项；部分任务创建失败，请检查存储空间后重试" else if (added > 0) "已加入 $added 个下载任务" else "所选资源已有下载任务，请在下载列表查看")
                        if (added > 0) startDownloads()
                    }
                }
            }
        }, previewVideo = { group, variant ->
            previewTask(DownloadTask(java.util.UUID.randomUUID().toString(), group.title, variant, variant.requestContext ?: group.context))
        }, retry = ::rescan).also { it.show(imagesFirst) }
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
                repository.all().filter { it.state in activeStates }.forEach { task -> repository.update(task.id) { old -> old.copy(state = TaskState.PAUSED, error = "系统暂不允许启动下载服务") } }
            }
        }
    }
    private fun previewTask(task: DownloadTask) {
        if (task.variant.blockedReason.isNotEmpty()) { toast(task.variant.blockedReason); return }
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("preview", DownloadRepository.encode(task).toString()))
    }
    private fun openDownloaded(task: DownloadTask) {
        val file = File(DownloadRepository.get(this).folder(task.id), task.output)
        if (!file.exists()) { toast("本地文件已不存在"); return }
        if (task.variant.kind == MediaKind.IMAGE) {
            io.execute {
                val repository = DownloadRepository.get(this)
                val tasks = repository.all().filter { it.state == TaskState.COMPLETE && it.variant.kind == MediaKind.IMAGE && File(repository.folder(it.id), it.output).exists() }
                runOnUiThread {
                    if (isDestroyed || tasks.none { it.id == task.id }) return@runOnUiThread
                    hideKeyboard()
                    imageGallery?.dismiss()
                    lateinit var viewer: ImageGalleryDialog
                    viewer = ImageGalleryDialog(this,
                        tasks.map { GalleryPicture(it.id, it.title, file = File(repository.folder(it.id), it.output)) },
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
            val result = runCatching { shareFile(task) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { file ->
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = if (task.variant.kind == MediaKind.HLS) "application/zip" else task.variant.mime.ifBlank { if (task.variant.kind == MediaKind.IMAGE) "image/*" else "video/*" }
                        putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = android.content.ClipData.newRawUri(task.title, uri)
                    }, if (task.variant.kind == MediaKind.HLS) "分享 HLS 离线资源包（ZIP，非 MP4）" else "分享媒体文件"))
                }.onFailure { toast("无法分享文件，请检查本地文件和存储空间") }
            }
        }
    }
    private fun shareFile(task: DownloadTask): File {
        val folder = DownloadRepository.get(this).folder(task.id)
        val file = File(folder, task.output)
        require(file.exists()) { "本地文件不存在" }
        if (task.variant.kind != MediaKind.HLS) return file
        val archive = File(folder, "offline-package.zip")
        val temporary = File(folder, "export.part")
        java.util.zip.ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
            folder.listFiles()?.filter { it.name == task.output || it.name.matches(Regex("part-[0-9]+\\.bin")) }?.sortedBy { it.name }?.forEach { part ->
                zip.putNextEntry(java.util.zip.ZipEntry(part.name)); part.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        if (archive.exists()) archive.delete()
        require(temporary.renameTo(archive))
        return archive
    }
    private fun exportDownloaded(task: DownloadTask) {
        exportTaskId = task.id
        val hls = task.variant.kind == MediaKind.HLS
        if (hls) toast("HLS 将导出为离线资源包 ZIP，解压后可播放，非单个 MP4")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (hls) "application/zip" else task.variant.mime.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_TITLE, safeFileName(task.title, if (hls) "zip" else extension(task.variant)))
        }, 701)
    }
    @Deprecated("Platform Activity result API retained for the native View shell")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 701 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val id = exportTaskId ?: return
            io.execute {
                val result = runCatching {
                    val task = DownloadRepository.get(this).get(id) ?: error("下载记录不存在")
                    val file = shareFile(task)
                    contentResolver.openOutputStream(uri, "wt")?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: error("无法写入目标位置")
                }
                runOnUiThread { if (!isDestroyed) toast(if (result.isSuccess) "导出完成，文件已保存到你选择的位置" else "导出失败，请检查目标位置和存储空间") }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("exportTask", exportTaskId)
        outState.putInt("section", section)
        outState.putString("downloadCategory", downloads.selectedCategory.name)
        super.onSaveInstanceState(outState)
    }
    private fun showDownloadHelp() {
        AlertDialog.Builder(this).setTitle("下载视频与图片").setMessage("打开网页并播放视频，点击右下方资源图标，选择版本或图片后下载。\n\n图标可长按查看说明；图片预览中轻点关闭、左右滑动切换、双指缩放。\n\n普通文件可分享与导出；未加密点播 HLS 保存为离线资源包，导出为 ZIP。\n\n暂不支持 YouTube、直播、加密 HLS、DRM 和分离音轨的离线下载。部分网站需要在原网页登录后重新检测。").setPositiveButton("知道了", null).show()
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    companion object { private const val PANEL_BODY = 10001 }
}
