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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import it.trisassist.accessibility.TrisAccessibilityService
import it.trisassist.capture.CaptureService
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlay: SuggestionOverlayView? = null
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var timerBubble: TextView? = null
    private var timerParams: WindowManager.LayoutParams? = null
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerStartedAt = 0L
    private var timerAlertSent = false
    private val timerTick = object : Runnable {
        override fun run() {
            if (timerStartedAt == 0L) return
            val elapsed = SystemClock.elapsedRealtime() - timerStartedAt
            if (!timerAlertSent && elapsed >= TIMER_LIMIT_MS) {
                timerAlertSent = true
                timerBubble?.let { styleTimerBubble(it, true) }
                vibrateTimerAlert()
                Toast.makeText(this@OverlayService, "Tempo: 2 minuti e 30", Toast.LENGTH_LONG).show()
            }
            timerHandler.postDelayed(this, 250L)
        }
    }
    private var armed = false
    private var autoMode = false

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
        addTimerBubble()

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

    private fun addTimerBubble() {
        val density = resources.displayMetrics.density
        val size = (30 * density).toInt()
        val prefs = getSharedPreferences("overlay", MODE_PRIVATE)
        timerBubble = TextView(this).apply {
            text = "T"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            elevation = 7f * density
            contentDescription = "Timer 2 minuti e 30"
        }
        timerParams = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("timer_x", (6 * density).toInt())
            y = prefs.getInt(
                "timer_y",
                (resources.displayMetrics.heightPixels * 0.30f).toInt()
            )
        }
        timerBubble?.let { styleTimerBubble(it, false) }
        attachTimerDragListener()
        windowManager.addView(timerBubble, timerParams)
    }

    private fun attachTimerDragListener() {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        timerBubble?.setOnTouchListener { _, event ->
            val params = timerParams ?: return@setOnTouchListener false
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
                    windowManager.updateViewLayout(timerBubble, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - downRawX) + abs(event.rawY - downRawY)
                    if (moved < 12f * resources.displayMetrics.density) {
                        toggleTimer()
                    }
                    getSharedPreferences("overlay", MODE_PRIVATE).edit()
                        .putInt("timer_x", params.x)
                        .putInt("timer_y", params.y)
                        .apply()
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleTimer() {
        if (timerStartedAt == 0L) {
            timerStartedAt = SystemClock.elapsedRealtime()
            timerAlertSent = false
            timerBubble?.let { styleTimerBubble(it, false, running = true) }
            timerHandler.removeCallbacks(timerTick)
            timerHandler.post(timerTick)
            Toast.makeText(this, "Timer avviato", Toast.LENGTH_SHORT).show()
        } else {
            resetTimer()
            Toast.makeText(this, "Timer azzerato", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetTimer() {
        timerStartedAt = 0L
        timerAlertSent = false
        timerHandler.removeCallbacks(timerTick)
        timerBubble?.let { styleTimerBubble(it, false) }
    }

    private fun styleTimerBubble(view: TextView, alert: Boolean, running: Boolean = false) {
        val fill = when {
            alert -> Color.rgb(210, 45, 45)
            running -> Color.rgb(35, 145, 75)
            else -> Color.argb(210, 55, 62, 68)
        }
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), Color.WHITE)
        }
    }

    private fun vibrateTimerAlert() {
        val vibrator = getSystemService(Vibrator::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0L, 250L, 140L, 400L),
                    -1
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(700L)
        }
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
                        if (held >= 650L) onBubbleLongPress() else onBubbleTap()
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

    private fun updateBubble() {
        bubble?.text = if (autoMode) "AUTO" else "1×"
        bubble?.textSize = if (autoMode) 10f else 15f
        val fill = when {
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
        timerHandler.removeCallbacks(timerTick)
        overlay?.let { windowManager.removeView(it) }
        bubble?.let { windowManager.removeView(it) }
        timerBubble?.let { windowManager.removeView(it) }
        overlay = null
        bubble = null
        bubbleParams = null
        timerBubble = null
        timerParams = null
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
        const val EXTRA_ACCESSIBILITY_READY = "accessibilityReady"
        private const val TIMER_LIMIT_MS = 150_000L
    }
}
