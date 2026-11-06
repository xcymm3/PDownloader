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

class MainActivity : Activity() {
    private data class Tab(
        val id: String = UUID.randomUUID().toString(),
        var url: String = "", var title: String = "新标签页",
        var web: WebView? = null, var state: Bundle? = null,
        var touched: Long = 0, var failed: Boolean = false
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BrowserStore(applicationContext)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        buildUi()
        applySystemBarAppearance()
        restoreSession()
        showTab(selectedId)
        handleIntent(intent)
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
        content = FrameLayout(this)
        browser.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = row()
        backButton = icon("back", "后退") { goBack() }
        forwardButton = icon("forward", "前进") { current.web?.goForward() }
        refreshButton = icon("reload", "刷新网页") { current.failed = false; current.web?.reload(); showTab(selectedId) }
        listOf(backButton, forwardButton, refreshButton).forEach { controls.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f)) }
        controls.addView(icon("star", "收藏当前网页") { bookmark() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        browser.addView(controls)
        placeholder = column().apply { gravity = Gravity.CENTER; visibility = View.GONE; setPadding(dp(24), dp(24), dp(24), dp(24)) }
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
        if (index != 0) {
            placeholder.removeAllViews()
            placeholder.addView(label(if (index == 1) "下载中" else "已下载", 25f))
            placeholder.addView(label("视频下载功能尚未开放", 15f, true).apply { setPadding(0, dp(16), 0, dp(24)) })
            placeholder.addView(action("返回浏览") { selectSection(0) })
        }
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
        home.addView(label("浅色 / 深色外观跟随系统", 13f, true))
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
                if (tab.web === view && !title.isNullOrBlank()) { tab.title = title; persistSession() }
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
        setDownloadListener { _, _, _, _, _ -> toast("视频下载功能尚未开放") }
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
        listOf("新建标签页", "收藏当前网页", "收藏夹", "历史记录", "分享网页").forEachIndexed { index, title -> popup.menu.add(0, index, index, title) }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> newTab()
                1 -> bookmark()
                2 -> showRecords("bookmarks")
                3 -> showRecords("history")
                4 -> if (current.url.isNotEmpty()) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, current.url) }, "分享网页")) else toast("请先打开网页")
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
        overlay.addView(action("退出全屏") { exitFullscreen() }.apply {
            setTextColor(color(R.color.video_foreground)); background = shape(R.color.video_background, 24)
        }, FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP or Gravity.END).apply { topMargin = dp(32); marginEnd = dp(12) })
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
    }
    override fun onPause() {
        exitFullscreen(); tabs.forEach { it.web?.onPause() }; persistSession(); super.onPause()
    }
    override fun onResume() { super.onResume(); if (tabs.isNotEmpty() && section == 0) current.web?.onResume() }
    override fun onDestroy() {
        pageDialog?.dismiss(); exitFullscreen(); tabs.forEach { release(it, false) }
        io.execute { store.close() }; io.shutdown(); super.onDestroy()
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    companion object { private const val PANEL_BODY = 10001 }
}
