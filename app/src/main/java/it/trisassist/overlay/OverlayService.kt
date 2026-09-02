package it.trisassist.overlay

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlay: LinearLayout? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 18, 28, 18)
            background = GradientDrawable().apply {
                setColor(0xCC183317.toInt())
                cornerRadius = 24f
            }
            addView(TextView(context).apply {
                text = "TRIS ASSIST ATTIVO"
                setTextColor(Color.rgb(32, 216, 58))
                textSize = 15f
            })
            addView(TextView(context).apply {
                text = "Analisi tessere…"
                setTextColor(Color.WHITE)
                textSize = 14f
            })
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 35
            y = 250
        }
        windowManager.addView(overlay, params)
    }

    override fun onDestroy() {
        overlay?.let { windowManager.removeView(it) }
        overlay = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
