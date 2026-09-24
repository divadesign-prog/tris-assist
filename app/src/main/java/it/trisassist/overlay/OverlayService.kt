package it.trisassist.overlay

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import it.trisassist.accessibility.TrisAccessibilityService
import it.trisassist.capture.CaptureService
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlay: SuggestionOverlayView? = null
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var armed = false
    private var autoMode = false
    private var autoPlusMode = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SUGGESTION -> {
                    val rects = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableArrayListExtra(EXTRA_RECTS, RectF::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra(EXTRA_RECTS)
                    } ?: arrayListOf()
                    overlay?.update(rects, intent.getStringExtra(EXTRA_MESSAGE) ?: "")
                }
                ACTION_CONTROL_STATE -> {
                    armed = intent.getBooleanExtra(EXTRA_ARMED, false)
                    autoMode = intent.getBooleanExtra(EXTRA_AUTO, false)
                    autoPlusMode = intent.getBooleanExtra(EXTRA_AUTO_PLUS, false)
                    updateBubble()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        addSuggestionLayer()
        addControlBubble()

        val filter = IntentFilter().apply {
            addAction(ACTION_SUGGESTION)
            addAction(ACTION_CONTROL_STATE)
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun addSuggestionLayer() {
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
    }

    private fun addControlBubble() {
        val size = (52 * resources.displayMetrics.density).toInt()
        val savedY = getSharedPreferences("overlay", MODE_PRIVATE)
            .getInt("bubble_y", (resources.displayMetrics.heightPixels * 0.56f).toInt())

        bubble = TextView(this).apply {
            text = "1×"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            elevation = 8f * resources.displayMetrics.density
        }
        bubbleParams = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (8 * resources.displayMetrics.density).toInt()
            y = savedY
        }
        updateBubble()
        attachDragListener()
        windowManager.addView(bubble, bubbleParams)
    }

    private fun attachDragListener() {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        bubble?.setOnTouchListener { _, event ->
            val params = bubbleParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + event.rawX - downRawX).toInt()
                        .coerceIn(0, resources.displayMetrics.widthPixels - params.width)
                    params.y = (startY + event.rawY - downRawY).toInt()
                        .coerceIn(0, resources.displayMetrics.heightPixels - params.height)
                    windowManager.updateViewLayout(bubble, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - downRawX) + abs(event.rawY - downRawY)
                    val held = event.eventTime - event.downTime
                    if (moved < 14f * resources.displayMetrics.density) {
                        when {
                            held >= 1600L -> onBubbleExtraLongPress()
                            held >= 650L -> onBubbleLongPress()
                            else -> onBubbleTap()
                        }
                    }
                    getSharedPreferences("overlay", MODE_PRIVATE)
                        .edit().putInt("bubble_y", params.y).apply()
                    true
                }
                else -> false
            }
        }
    }

    private fun onBubbleTap() {
        if (!TrisAccessibilityService.isReady()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        }
        startService(Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_ONE_TRIPLE
        })
    }

    private fun onBubbleLongPress() {
        if (!TrisAccessibilityService.isReady()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        }
        startService(Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_AUTO
        })
    }

    private fun onBubbleExtraLongPress() {
        if (!TrisAccessibilityService.isReady()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        }
        startService(Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_AUTO_PLUS
        })
    }

    private fun updateBubble() {
        bubble?.text = when {
            autoPlusMode -> "AUTO+"
            autoMode -> "AUTO"
            else -> "1×"
        }
        bubble?.textSize = if (autoMode || autoPlusMode) 10f else 15f
        val fill = when {
            autoPlusMode -> Color.rgb(132, 74, 190)
            autoMode -> Color.rgb(31, 190, 72)
            armed -> Color.rgb(238, 160, 35)
            else -> Color.argb(205, 55, 62, 68)
        }
        bubble?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke((2 * resources.displayMetrics.density).toInt(), Color.WHITE)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        overlay?.let { windowManager.removeView(it) }
        bubble?.let { windowManager.removeView(it) }
        overlay = null
        bubble = null
        bubbleParams = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SUGGESTION = "it.trisassist.SUGGESTION"
        const val ACTION_CONTROL_STATE = "it.trisassist.CONTROL_STATE"
        const val EXTRA_RECTS = "rects"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_ARMED = "armed"
        const val EXTRA_AUTO = "auto"
        const val EXTRA_AUTO_PLUS = "autoPlus"
        const val EXTRA_ACCESSIBILITY_READY = "accessibilityReady"
    }
}
