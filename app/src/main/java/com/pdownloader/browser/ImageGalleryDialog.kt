package com.pdownloader.browser

import android.app.Activity
import android.app.Dialog
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.pdownloader.browser.media.RequestContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class GalleryPicture(val id: String, val title: String, val url: String = "", val file: File? = null, val context: RequestContext? = null)

/** Shared lightbox for discovered and downloaded images. A single tap closes; pinch zooms. */
class ImageGalleryDialog(
    private val activity: Activity,
    private val pictures: List<GalleryPicture>,
    initialIndex: Int,
    private val images: MediaImages,
    private val isSelected: ((String) -> Boolean)? = null,
    private val select: ((String, Boolean) -> Unit)? = null,
    private val selectedCount: () -> Int = { 0 },
    private val download: (() -> Unit)? = null,
    private val share: ((GalleryPicture) -> Unit)? = null,
    private val export: ((GalleryPicture) -> Unit)? = null,
    onDismiss: () -> Unit = {}
) : Dialog(activity, R.style.ImageViewerTheme) {
    private var index = initialIndex.coerceIn(0, max(0, pictures.lastIndex))
    private val image = GalleryImageView(activity)
    private val counter = activity.label("", 14f).apply { gravity = Gravity.CENTER; setTextColor(activity.color(R.color.video_foreground)) }
    private val check = CheckBox(activity).apply {
        contentDescription = "选择当前图片"; tooltipText = "选择当前图片"
        buttonDrawable = activity.getDrawable(R.drawable.media_selection)
        buttonTintList = null
        background = null; setPadding(activity.dp(12), 0, activity.dp(12), 0)
        visibility = if (select != null) View.VISIBLE else View.GONE
    }
    private val previous = icon("back", "上一张") { goTo(index - 1) }
    private val next = icon("forward", "下一张") { goTo(index + 1) }
    private val downloadButton = icon("download", "下载已选资源") { download?.invoke() }
    private val loading = ProgressBar(activity)
    private val error = activity.label("暂时无法显示图片", 14f).apply { gravity = Gravity.CENTER; setTextColor(activity.color(R.color.video_foreground)); visibility = View.GONE }

    init {
        require(pictures.isNotEmpty())
        val root = FrameLayout(activity).apply { setBackgroundColor(activity.color(R.color.gallery_scrim)); setOnClickListener { dismiss() } }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        image.contentDescription = "图片预览，轻点关闭，双指缩放，左右滑动切换"
        image.setOnClickListener { dismiss() }
        image.navigate = { delta -> goTo(index + delta) }
        root.addView(loading, FrameLayout.LayoutParams(activity.dp(32), activity.dp(32), Gravity.CENTER))
        root.addView(error, FrameLayout.LayoutParams(-1, activity.dp(64), Gravity.CENTER))
        val top = activity.row().apply { setBackgroundColor(activity.color(R.color.gallery_controls)); setPadding(activity.dp(4), 0, activity.dp(4), 0) }
        top.addView(icon("close", "关闭图片预览") { dismiss() }, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)))
        top.addView(counter, LinearLayout.LayoutParams(0, activity.dp(48), 1f))
        top.addView(check, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)))
        root.addView(top, FrameLayout.LayoutParams(-1, activity.dp(48), Gravity.TOP))
        val bottom = activity.row().apply { gravity = Gravity.CENTER; setBackgroundColor(activity.color(R.color.gallery_controls)) }
        bottom.addView(previous, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)))
        if (download != null) bottom.addView(downloadButton, LinearLayout.LayoutParams(activity.dp(56), activity.dp(48)))
        if (share != null) bottom.addView(icon("share", "分享图片") { share.invoke(pictures[index]) }, LinearLayout.LayoutParams(activity.dp(56), activity.dp(48)))
        if (export != null) bottom.addView(icon("download", "导出图片") { export.invoke(pictures[index]) }, LinearLayout.LayoutParams(activity.dp(56), activity.dp(48)))
        bottom.addView(next, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)))
        root.addView(bottom, FrameLayout.LayoutParams(-1, activity.dp(56), Gravity.BOTTOM))
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                root.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(root)
        setOnShowListener {
            window?.apply {
                setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                setLayout(-1, -1)
                if (Build.VERSION.SDK_INT >= 30) {
                    setDecorFitsSystemWindows(false)
                    insetsController?.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
                }
                decorView.requestApplyInsets()
            }
            render()
        }
        setOnDismissListener { image.tag = null; image.setImageDrawable(null); onDismiss() }
        refreshSelection()
    }

    fun goTo(position: Int) {
        if (position !in pictures.indices || position == index) return
        index = position; render()
    }
    fun refreshSelection() {
        counter.text = if (select == null) "${index + 1} / ${pictures.size}" else activity.getString(R.string.image_page, index + 1, pictures.size, selectedCount())
        check.setOnCheckedChangeListener(null)
        check.isChecked = isSelected?.invoke(pictures[index].id) == true
        check.setOnCheckedChangeListener { _, value -> select?.invoke(pictures[index].id, value); refreshSelection() }
        downloadButton.badgeCount = selectedCount()
        downloadButton.isEnabled = selectedCount() > 0
        previous.isEnabled = index > 0; next.isEnabled = index < pictures.lastIndex
    }
    private fun render() {
        refreshSelection()
        val picture = pictures[index]
        loading.visibility = View.VISIBLE; error.visibility = View.GONE
        image.setImageDrawable(null)
        val result: (Boolean) -> Unit = { ok ->
            if (isShowing) { loading.visibility = View.GONE; error.visibility = if (ok) View.GONE else View.VISIBLE }
        }
        if (picture.file != null) images.loadFile(image, picture.file, result)
        else if (picture.context != null) images.loadPreview(image, picture.url, picture.context, result)
        else { loading.visibility = View.GONE; error.visibility = View.VISIBLE }
    }
    private fun icon(kind: String, description: String, action: () -> Unit) = BrowserIcon(activity, kind, description, action).apply {
        foregroundColor = activity.color(R.color.video_foreground)
    }
}

/** Matrix coordinates stay in the viewport, so pan and pinch do not move the touch target. */
internal class GalleryImageView(context: android.content.Context) : ImageView(context) {
    var navigate: (Int) -> Unit = {}
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val transform = Matrix()
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { performClick(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { zoom = if (zoom > 1f) 1f else 2.5f; panX = 0f; panY = 0f; updateTransform(); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (zoom > 1f) { panX -= distanceX; panY -= distanceY; updateTransform() }
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val delta = e2.x - (e1?.x ?: e2.x)
            if (zoom == 1f && abs(delta) > context.dp(40) && abs(velocityX) > abs(velocityY)) navigate(if (delta < 0) 1 else -1)
            return true
        }
    })
    private val scales = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val nextZoom = (zoom * detector.scaleFactor).coerceIn(1f, 5f)
            val factor = nextZoom / zoom
            panX = (width / 2f + panX - detector.focusX) * factor + detector.focusX - width / 2f
            panY = (height / 2f + panY - detector.focusY) * factor + detector.focusY - height / 2f
            zoom = nextZoom; updateTransform(); return true
        }
    })
    init { scaleType = ScaleType.MATRIX; isClickable = true; isFocusable = true }
    override fun setImageDrawable(drawable: Drawable?) { super.setImageDrawable(drawable); zoom = 1f; panX = 0f; panY = 0f; updateTransform() }
    override fun setImageBitmap(bitmap: android.graphics.Bitmap?) { super.setImageBitmap(bitmap); zoom = 1f; panX = 0f; panY = 0f; updateTransform() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); updateTransform() }
    private fun updateTransform() {
        val picture = drawable ?: return
        if (width <= 0 || height <= 0 || picture.intrinsicWidth <= 0 || picture.intrinsicHeight <= 0) return
        val base = min(width.toFloat() / picture.intrinsicWidth, height.toFloat() / picture.intrinsicHeight)
        val scaledWidth = picture.intrinsicWidth * base * zoom
        val scaledHeight = picture.intrinsicHeight * base * zoom
        val limitX = max(0f, (scaledWidth - width) / 2f); val limitY = max(0f, (scaledHeight - height) / 2f)
        panX = panX.coerceIn(-limitX, limitX); panY = panY.coerceIn(-limitY, limitY)
        transform.setScale(base * zoom, base * zoom)
        transform.postTranslate((width - scaledWidth) / 2f + panX, (height - scaledHeight) / 2f + panY)
        imageMatrix = transform
    }
    // GestureDetector's confirmed-tap callback invokes performClick and emits accessibility events.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scales.onTouchEvent(event); gestures.onTouchEvent(event)
        return true
    }
    override fun performClick(): Boolean = super.performClick()
}
