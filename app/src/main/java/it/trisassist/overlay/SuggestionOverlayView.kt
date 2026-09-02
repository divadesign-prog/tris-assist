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
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC183317.toInt()
        style = Paint.Style.FILL
    }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(32, 216, 58)
        textSize = 31f
        isFakeBoldText = true
    }
    private val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 27f
    }

    private var suggestions: List<RectF> = emptyList()
    private var message: String = "Analisi tessere…"

    fun update(rects: List<RectF>, text: String) {
        suggestions = rects
        message = text
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val panelRect = RectF(30f, 250f, 455f, 365f)
        canvas.drawRoundRect(panelRect, 22f, 22f, panel)
        canvas.drawText("TRIS ASSIST ATTIVO", 55f, 292f, title)
        canvas.drawText(message, 55f, 337f, subtitle)

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
