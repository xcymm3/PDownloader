package com.pdownloader.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
internal fun Context.color(id: Int) = getColor(id)
internal fun Context.shape(fill: Int, radius: Int = 16) = GradientDrawable().apply {
    setColor(color(fill)); cornerRadius = dp(radius).toFloat()
}
internal fun Context.label(value: String, size: Float = 14f, muted: Boolean = false) = TextView(this).apply {
    text = value; textSize = size
    setTextColor(color(if (muted) R.color.muted else R.color.ink))
    gravity = Gravity.CENTER_VERTICAL
}
internal fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
internal fun Context.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
internal fun Context.action(value: String, block: () -> Unit) = label(value).apply {
    setSingleLine(true)
    gravity = Gravity.CENTER; minHeight = dp(48); isClickable = true; isFocusable = true
    setPadding(dp(16), dp(8), dp(16), dp(8))
    background = RippleDrawable(ColorStateList.valueOf(color(R.color.accent_surface)), shape(R.color.surface), null)
    setOnClickListener { block() }
}

internal class BrowserIcon @JvmOverloads constructor(context: Context, private val kind: String = "home", description: String = "", onClick: () -> Unit = {}) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.9f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    var foregroundColor: Int? = null
        set(value) { field = value; invalidate() }
    var glyphSizeDp = 22
    var badgeCount = 0
        set(value) { field = value; invalidate() }
    var counter: Int = 0
        set(value) { field = value; invalidate() }
    var selectedIcon: Boolean = false
        set(value) { field = value; invalidate() }
    init {
        contentDescription = description; isClickable = true; isFocusable = true
        tooltipText = description
        minimumWidth = context.dp(48); minimumHeight = context.dp(48)
        background = RippleDrawable(ColorStateList.valueOf(context.color(R.color.accent_surface)), null, context.shape(R.color.surface))
        setOnClickListener { onClick() }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        val unit = context.dp(glyphSizeDp).toFloat() / 24
        canvas.translate((width - 24 * unit) / 2, (height - 24 * unit) / 2)
        canvas.scale(unit, unit)
        paint.color = foregroundColor ?: context.color(if (selectedIcon) R.color.accent else R.color.ink)
        paint.alpha = if (isEnabled) 255 else 85
        paint.style = Paint.Style.STROKE
        fun line(vararg coordinates: Float) {
            path.reset(); path.moveTo(coordinates[0], coordinates[1])
            for (i in 2 until coordinates.size step 2) path.lineTo(coordinates[i], coordinates[i + 1])
            canvas.drawPath(path, paint)
        }
        when (kind) {
            "settings" -> {
                line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 20f, 18f)
                line(9f, 3f, 9f, 9f); line(15f, 9f, 15f, 15f); line(9f, 15f, 9f, 21f)
            }
            "info" -> { canvas.drawCircle(12f, 12f, 9f, paint); line(12f, 7f, 12f, 7.1f); line(12f, 11f, 12f, 17f) }
            "help" -> { canvas.drawCircle(12f, 12f, 9f, paint); canvas.drawArc(9f, 6f, 15f, 12f, 180f, 230f, false, paint); line(12f, 12f, 12f, 14f); line(12f, 18f, 12f, 18.1f) }
            "search" -> { canvas.drawCircle(10f, 10f, 6f, paint); line(15f, 15f, 21f, 21f) }
            "history" -> { canvas.drawArc(4f, 4f, 21f, 21f, -130f, 310f, false, paint); line(3f, 3f, 3f, 9f, 9f, 9f); line(12f, 7f, 12f, 13f, 16f, 15f) }
            "delete" -> { line(4f, 6f, 20f, 6f); line(9f, 3f, 15f, 3f); line(6f, 7f, 7f, 21f, 17f, 21f, 18f, 7f); line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f) }
            "globe" -> { canvas.drawCircle(12f, 12f, 9f, paint); canvas.drawOval(8f, 3f, 16f, 21f, paint); line(3f, 12f, 21f, 12f); line(5f, 7f, 19f, 7f); line(5f, 17f, 19f, 17f) }
            "back" -> { line(14f, 5f, 7f, 12f, 14f, 19f) }
            "forward" -> { line(9f, 5f, 16f, 12f, 9f, 19f) }
            "close" -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            "resources" -> { line(3f, 7f, 12f, 2f, 21f, 7f, 12f, 12f, 3f, 7f); line(3f, 12f, 12f, 17f, 21f, 12f); line(3f, 17f, 12f, 22f, 21f, 17f) }
            "image" -> { canvas.drawRoundRect(3f, 3f, 21f, 21f, 2f, 2f, paint); canvas.drawCircle(8f, 8f, 1.5f, paint); line(4f, 18f, 10f, 12f, 14f, 16f, 17f, 12f, 21f, 17f) }
            "play" -> line(8f, 4f, 20f, 12f, 8f, 20f, 8f, 4f)
            "pause" -> { line(8f, 5f, 8f, 19f); line(16f, 5f, 16f, 19f) }
            "edit" -> { line(4f, 16f, 16f, 4f, 20f, 8f, 8f, 20f, 3f, 21f, 4f, 16f); line(13f, 7f, 17f, 11f) }
            "select_all" -> { canvas.drawRoundRect(3f, 3f, 21f, 21f, 2f, 2f, paint); line(7f, 12f, 10f, 15f, 17f, 8f) }
            "clear_selection" -> { canvas.drawRoundRect(3f, 3f, 21f, 21f, 2f, 2f, paint); line(8f, 12f, 16f, 12f) }
            "sort" -> { line(4f, 6f, 20f, 6f); line(7f, 12f, 17f, 12f); line(10f, 18f, 14f, 18f) }
            "share" -> { canvas.drawCircle(6f, 12f, 2.5f, paint); canvas.drawCircle(18f, 5f, 2.5f, paint); canvas.drawCircle(18f, 19f, 2.5f, paint); line(8f, 11f, 16f, 6f); line(8f, 13f, 16f, 18f) }
            "external" -> { line(13f, 3f, 21f, 3f, 21f, 11f); line(21f, 3f, 11f, 13f); line(9f, 5f, 4f, 5f, 4f, 20f, 19f, 20f, 19f, 15f) }
            "collapse" -> { line(3f, 8f, 8f, 8f, 8f, 3f); line(16f, 3f, 16f, 8f, 21f, 8f); line(3f, 16f, 8f, 16f, 8f, 21f); line(16f, 21f, 16f, 16f, 21f, 16f) }
            "plus" -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            "menu" -> { for (y in listOf(5f, 12f, 19f)) { paint.style = Paint.Style.FILL; canvas.drawCircle(12f, y, 1.6f, paint) } }
            "windows" -> { canvas.drawRoundRect(7f, 3f, 21f, 17f, 2f, 2f, paint); line(3f, 7f, 3f, 21f, 17f, 21f) }
            "tabs" -> {
                canvas.drawRoundRect(3f, 3f, 21f, 21f, 3f, 3f, paint)
                paint.style = Paint.Style.FILL; paint.textSize = 11f; paint.textAlign = Paint.Align.CENTER; paint.isFakeBoldText = true
                canvas.drawText(if (counter > 99) "∞" else counter.toString(), 12f, 16f, paint); paint.isFakeBoldText = false
            }
            "home" -> { line(3f, 11f, 12f, 3f, 21f, 11f); line(6f, 9f, 6f, 21f, 10f, 21f, 10f, 15f, 14f, 15f, 14f, 21f, 18f, 21f, 18f, 9f) }
            "download" -> { line(12f, 3f, 12f, 15f, 7f, 10f); line(12f, 15f, 17f, 10f); line(4f, 16f, 4f, 21f, 20f, 21f, 20f, 16f) }
            "done" -> { canvas.drawRoundRect(3f, 3f, 21f, 21f, 3f, 3f, paint); line(7f, 12f, 10f, 15f, 17f, 8f) }
            "reload" -> { canvas.drawArc(4f, 4f, 20f, 20f, 35f, 290f, false, paint); line(15f, 3f, 20f, 5f, 18f, 10f) }
            "star" -> { line(12f, 3f, 15f, 9f, 22f, 10f, 17f, 15f, 18f, 22f, 12f, 18f, 6f, 22f, 7f, 15f, 2f, 10f, 9f, 9f, 12f, 3f) }
            else -> { canvas.drawCircle(12f, 12f, 9f, paint); line(12f, 6f, 12f, 12f, 16f, 14f) }
        }
        if (badgeCount > 0) {
            paint.style = Paint.Style.FILL; paint.color = context.color(R.color.surface)
            canvas.drawCircle(23f, 2f, 8f, paint)
            paint.color = context.color(R.color.accent); paint.textAlign = Paint.Align.CENTER; paint.textSize = 9f
            canvas.drawText(if (badgeCount > 99) "99+" else badgeCount.toString(), 23f, 5f, paint)
        }
        canvas.restore()
    }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.ImageButton"
}
