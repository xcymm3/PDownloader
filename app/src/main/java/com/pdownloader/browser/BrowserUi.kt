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
    gravity = Gravity.CENTER; minHeight = dp(48); isClickable = true; isFocusable = true
    setPadding(dp(16), dp(8), dp(16), dp(8))
    background = RippleDrawable(ColorStateList.valueOf(color(R.color.accent_surface)), shape(R.color.surface), null)
    setOnClickListener { block() }
}

internal class BrowserIcon @JvmOverloads constructor(context: Context, private val kind: String = "home", description: String = "", onClick: () -> Unit = {}) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.9f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    var counter: Int = 0
        set(value) { field = value; invalidate() }
    var selectedIcon: Boolean = false
        set(value) { field = value; invalidate() }
    init {
        contentDescription = description; isClickable = true; isFocusable = true
        minimumWidth = context.dp(48); minimumHeight = context.dp(48)
        background = RippleDrawable(ColorStateList.valueOf(context.color(R.color.accent_surface)), null, context.shape(R.color.surface))
        setOnClickListener { onClick() }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        val unit = context.dp(24).toFloat() / 24
        canvas.translate((width - 24 * unit) / 2, (height - 24 * unit) / 2)
        canvas.scale(unit, unit)
        paint.color = context.color(if (selectedIcon) R.color.accent else R.color.ink)
        paint.alpha = if (isEnabled) 255 else 85
        paint.style = Paint.Style.STROKE
        fun line(vararg coordinates: Float) {
            path.reset(); path.moveTo(coordinates[0], coordinates[1])
            for (i in 2 until coordinates.size step 2) path.lineTo(coordinates[i], coordinates[i + 1])
            canvas.drawPath(path, paint)
        }
        when (kind) {
            "back" -> { line(14f, 5f, 7f, 12f, 14f, 19f) }
            "forward" -> { line(9f, 5f, 16f, 12f, 9f, 19f) }
            "close" -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            "plus" -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            "menu" -> { for (y in listOf(5f, 12f, 19f)) { paint.style = Paint.Style.FILL; canvas.drawCircle(12f, y, 1.6f, paint) } }
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
        canvas.restore()
    }
}
