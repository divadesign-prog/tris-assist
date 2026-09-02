package it.trisassist.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class SuggestionOverlayView(context: Context) : View(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(32, 216, 58)
        style = Paint.Style.STROKE
        strokeWidth = 9f
    }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(32, 216, 58)
        style = Paint.Style.FILL
    }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 34f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
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

        suggestions.forEachIndexed { index, rect ->
            val expanded = RectF(rect).apply { inset(-7f, -7f) }
            canvas.drawRoundRect(expanded, 18f, 18f, ring)
            val cx = expanded.right - 4f
            val cy = expanded.top + 4f
            canvas.drawCircle(cx, cy, 27f, badge)
            canvas.drawText((index + 1).toString(), cx, cy + 11f, badgeText)
        }
    }
}
