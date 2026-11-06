package com.pdownloader.browser

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

internal data class HomeSite(val title: String, val url: String, val mark: String)

/** Hallmark · pre-emit critique: P4 H4 E4 S5 R5 V4. Existing native theme and system type. */
@android.annotation.SuppressLint("ViewConstructor")
class HomePageView(
    private val activity: Activity,
    private val navigate: (String) -> Unit,
    bookmarks: () -> Unit,
    history: () -> Unit
) : ScrollView(activity) {
    internal val sites = listOf(
        HomeSite("百度", "https://www.baidu.com/", "baidu"),
        HomeSite("Google", "https://www.google.com/", "google"),
        HomeSite("哔哩哔哩", "https://www.bilibili.com/", "bilibili"),
        HomeSite("YouTube", "https://www.youtube.com/", "youtube"),
        HomeSite("X", "https://x.com/", "x"),
        HomeSite("Instagram", "https://www.instagram.com/", "instagram")
    )
    private val search: EditText
    var query: String
        get() = search.text.toString()
        set(value) { search.setText(value) }

    init {
        isFillViewport = true
        setBackgroundColor(activity.color(R.color.surface))
        val body = activity.column().apply { setPadding(activity.dp(20), activity.dp(64), activity.dp(20), activity.dp(32)) }
        body.addView(activity.label("PDownloader", 26f).apply {
            gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(-1, activity.dp(48)))
        val field = activity.row().apply { background = activity.shape(R.color.home_search, 30) }
        field.addView(BrowserIcon(activity, "search", "搜索") {}.apply {
            isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = null
        }, LinearLayout.LayoutParams(activity.dp(44), activity.dp(56)))
        search = EditText(activity).apply {
            contentDescription = "首页搜索或输入网址"; hint = "搜索或输入网址"
            textSize = 16f; isSingleLine = true
            setTextColor(activity.color(R.color.ink)); setHintTextColor(activity.color(R.color.muted))
            background = null; setPadding(0, 0, 0, 0)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_GO || (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_UP)) {
                    submit(); true
                } else false
            }
        }
        field.addView(search, LinearLayout.LayoutParams(0, activity.dp(56), 1f))
        field.addView(BrowserIcon(activity, "forward", "打开网址或开始搜索") { submit() }, LinearLayout.LayoutParams(activity.dp(48), activity.dp(56)))
        body.addView(field, LinearLayout.LayoutParams(-1, activity.dp(56)).apply { topMargin = activity.dp(16); bottomMargin = activity.dp(40) })
        sites.chunked(3).forEach { rowSites ->
            val row = activity.row()
            rowSites.forEach { site ->
                val tile = activity.column().apply {
                    gravity = Gravity.CENTER; isClickable = true; isFocusable = true
                    contentDescription = "打开${site.title}，${site.url}"
                    background = RippleDrawable(ColorStateList.valueOf(activity.color(R.color.accent_surface)), activity.shape(R.color.home_tile, 18), null)
                    setOnClickListener { navigate(site.url) }
                }
                tile.addView(SiteMarkView(activity, site.mark), LinearLayout.LayoutParams(activity.dp(40), activity.dp(40)))
                tile.addView(activity.label(site.title, 13f).apply {
                    gravity = Gravity.CENTER; setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(activity.dp(2), 0, activity.dp(2), 0)
                }, LinearLayout.LayoutParams(-1, activity.dp(32)).apply { topMargin = activity.dp(6) })
                row.addView(tile, LinearLayout.LayoutParams(0, activity.dp(112), 1f).apply { setMargins(activity.dp(4), 0, activity.dp(4), 0) })
            }
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.dp(12) })
        }
        val tools = activity.row().apply { gravity = Gravity.CENTER }
        listOf(Triple("star", "收藏夹", bookmarks), Triple("history", "历史记录", history)).forEach { (kind, title, action) ->
            val item = activity.column().apply {
                gravity = Gravity.CENTER; isClickable = true; isFocusable = true; contentDescription = title
                setOnClickListener { action() }
            }
            item.addView(BrowserIcon(activity, kind, title, action), LinearLayout.LayoutParams(activity.dp(48), activity.dp(44)))
            item.addView(activity.label(title, 12f, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, activity.dp(24)))
            tools.addView(item, LinearLayout.LayoutParams(activity.dp(104), -2))
        }
        body.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(20) })
        addView(body)
        // Keep the keyboard closed until the user chooses the search field.
        body.isFocusableInTouchMode = true; body.requestFocus()
    }

    fun submit(): Boolean {
        val url = AddressResolver.resolve(query)
        if (url == null) { search.error = "请输入有效的网址或搜索内容"; return false }
        navigate(url); return true
    }
}

/** Local vector-like site marks; the home page never sends requests to fetch favicons. */
@android.annotation.SuppressLint("ViewConstructor")
private class SiteMarkView(context: android.content.Context, private val kind: String) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val googleArcs = listOf(Triple(45f, 90f, R.color.site_google_green), Triple(135f, 65f, R.color.site_google_yellow), Triple(200f, 110f, R.color.site_google_red), Triple(0f, 45f, R.color.site_google_blue))
    private val pawDots = listOf(7f to 18f, 17f to 9f, 31f to 8f, 41f to 18f)
    private val instagramGradient = LinearGradient(5f, 43f, 43f, 3f, intArrayOf(context.color(R.color.site_instagram_yellow), context.color(R.color.site_instagram_pink), context.color(R.color.site_instagram_purple)), null, Shader.TileMode.CLAMP)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save(); canvas.scale(width / 48f, height / 48f)
        fun fill(color: Int) { paint.color = color; paint.style = Paint.Style.FILL; paint.shader = null }
        fun token(id: Int) = context.color(id)
        when (kind) {
            "google" -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 8f
                googleArcs.forEach { (start, sweep, color) ->
                    paint.color = token(color); canvas.drawArc(6f, 6f, 42f, 42f, start, sweep, false, paint)
                }
                fill(token(R.color.site_google_blue)); canvas.drawRect(24f, 21f, 45f, 28f, paint)
            }
            "baidu" -> {
                fill(token(R.color.site_baidu)); canvas.drawOval(12f, 21f, 37f, 43f, paint)
                for ((x, y) in pawDots) canvas.drawOval(x - 4, y - 6, x + 4, y + 6, paint)
                fill(token(R.color.site_white)); paint.textSize = 17f; paint.typeface = Typeface.DEFAULT_BOLD; paint.textAlign = Paint.Align.CENTER
                canvas.drawText("du", 24.5f, 36f, paint)
            }
            "bilibili" -> {
                fill(token(R.color.site_bilibili)); paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
                canvas.drawRoundRect(4f, 12f, 44f, 40f, 5f, 5f, paint)
                canvas.drawLine(14f, 4f, 21f, 12f, paint); canvas.drawLine(34f, 4f, 27f, 12f, paint)
                canvas.drawLine(14f, 22f, 14f, 30f, paint); canvas.drawLine(34f, 22f, 34f, 30f, paint)
            }
            "youtube" -> {
                fill(token(R.color.site_youtube)); canvas.drawRoundRect(1f, 8f, 47f, 40f, 9f, 9f, paint)
                fill(token(R.color.site_white)); path.reset(); path.moveTo(20f, 16f); path.lineTo(33f, 24f); path.lineTo(20f, 32f); path.close(); canvas.drawPath(path, paint)
            }
            "x" -> {
                fill(token(R.color.site_black)); canvas.drawRoundRect(0f, 0f, 48f, 48f, 12f, 12f, paint)
                fill(token(R.color.site_white)); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f
                path.reset(); path.moveTo(12f, 10f); path.lineTo(19f, 10f); path.lineTo(37f, 38f); path.lineTo(30f, 38f); path.close(); canvas.drawPath(path, paint)
                canvas.drawLine(35f, 10f, 13f, 38f, paint)
            }
            "instagram" -> {
                fill(token(R.color.site_instagram_pink))
                paint.shader = instagramGradient
                canvas.drawRoundRect(0f, 0f, 48f, 48f, 13f, 13f, paint)
                fill(token(R.color.site_white)); paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
                canvas.drawRoundRect(10f, 10f, 38f, 38f, 8f, 8f, paint); canvas.drawCircle(24f, 24f, 7f, paint)
                paint.style = Paint.Style.FILL; canvas.drawCircle(33f, 15f, 2f, paint)
            }
        }
        canvas.restore()
    }
}
