package it.trisassist.overlay

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.core.content.ContextCompat

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlay: SuggestionOverlayView? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_SUGGESTION) return
            val rects = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayListExtra(EXTRA_RECTS, RectF::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(EXTRA_RECTS)
            } ?: arrayListOf()
            overlay?.update(rects, intent.getStringExtra(EXTRA_MESSAGE) ?: "Analisi tessere…")
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        overlay = SuggestionOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        windowManager.addView(overlay, params)
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(ACTION_SUGGESTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        overlay?.let { windowManager.removeView(it) }
        overlay = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SUGGESTION = "it.trisassist.SUGGESTION"
        const val EXTRA_RECTS = "rects"
        const val EXTRA_MESSAGE = "message"
    }
}
