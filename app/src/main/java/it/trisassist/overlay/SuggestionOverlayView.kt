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

    private var suggestions: List<RectF> = emptyList()

    fun update(rects: List<RectF>, text: String) {
        suggestions = rects
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val statusBarOffset = if (Build.VERSION.SDK_INT >= 30) {
            rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top?.toFloat() ?: 0f
        } else {
            0f
        }
        suggestions.forEach { rect ->
            val cx = rect.centerX()
            val cy = rect.centerY() - statusBarOffset
            canvas.drawCircle(cx, cy, 17f, dotBorder)
            canvas.drawCircle(cx, cy, 12f, dot)
        }
    }
}
