package it.trisassist.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.WindowInsets

class SuggestionOverlayView(context: Context) : View(context) {
    private val dotBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(32, 216, 58)
        style = Paint.Style.FILL
    }

    private val adviceBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 35, 45, 35)
        style = Paint.Style.FILL
    }
    private val adviceText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 24f * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
    }

    private var suggestions: List<RectF> = emptyList()
    private var message: String = ""

    fun update(rects: List<RectF>, text: String) {
        suggestions = rects
        message = text
        invalidate()
    }

    private fun statusBarHeight(): Float {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id).toFloat()
        else 24f * resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val statusBarOffset = if (Build.VERSION.SDK_INT >= 30) {
            rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top?.toFloat()
                ?.takeIf { it > 0f } ?: statusBarHeight()
        } else {
            0f
        }
        suggestions.forEach { rect ->
            val cx = rect.centerX()
            val cy = rect.centerY() - statusBarOffset
            canvas.drawCircle(cx, cy, 17f, dotBorder)
            canvas.drawCircle(cx, cy, 12f, dot)
        }
        if (message.startsWith("TOGLI:")) {
            val centerX = width / 2f
            val baseline = statusBarOffset + 150f * resources.displayMetrics.density
            val halfWidth = adviceText.measureText(message) / 2f + 22f * resources.displayMetrics.density
            val box = RectF(
                centerX - halfWidth,
                baseline - 38f * resources.displayMetrics.density,
                centerX + halfWidth,
                baseline + 14f * resources.displayMetrics.density
            )
            canvas.drawRoundRect(box, 18f, 18f, adviceBackground)
            canvas.drawText(message, centerX, baseline, adviceText)
        }
    }
}
