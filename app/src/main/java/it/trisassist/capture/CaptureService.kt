package it.trisassist.capture

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import it.trisassist.overlay.OverlayService
import it.trisassist.vision.GamePhase
import it.trisassist.vision.GameState
import it.trisassist.vision.MovePlanner
import it.trisassist.vision.TileRecognizer

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var analysisThread: HandlerThread? = null
    private var stopping = false
    private var lastAnalysis = 0L
    private val recognizer by lazy { TileRecognizer() }
    private val planner = MovePlanner()

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            ACTION_START -> startCapture(intent)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        stopping = false
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Tris Assist attivo")
                .setContentText("Analisi dello schermo in corso")
                .setOngoing(true)
                .build()
        )

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null) return stopCapture()

        val wm = getSystemService(WindowManager::class.java)
        val bounds = wm.maximumWindowMetrics.bounds
        val density = resources.displayMetrics.densityDpi
        reader = ImageReader.newInstance(
            bounds.width(),
            bounds.height(),
            PixelFormat.RGBA_8888,
            2
        )
        projection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!stopping) stopCapture()
            }
        }, null)

        analysisThread = HandlerThread("TrisAssistVision").apply { start() }
        reader?.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = System.currentTimeMillis()
            if (now - lastAnalysis < 650L) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastAnalysis = now
            runCatching {
                val bitmap = imageToBitmap(image)
                image.close()
                analyze(bitmap)
                bitmap.recycle()
            }.onFailure {
                image.close()
                publish(emptyList(), "Riprovo analisi…")
            }
        }, Handler(requireNotNull(analysisThread).looper))

        display = projection?.createVirtualDisplay(
            "TrisAssistCapture",
            bounds.width(),
            bounds.height(),
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface,
            null,
            null
        )
        startService(Intent(this, OverlayService::class.java))
    }

    private fun analyze(bitmap: Bitmap) {
        val frame = recognizer.recognize(bitmap)
        val state = GameState(
            tiles = frame.boardTiles,
            tray = frame.tray,
            order = frame.order,
            observingOtherPlayer = false,
            publicStorageUnlocked = false,
            phase = GamePhase.PLAYING
        )
        val suggestion = planner.suggest(state)
        when {
            frame.boardTiles.isEmpty() -> publish(emptyList(), "Cerco tessere…")
            suggestion == null -> publish(emptyList(), "Nessun tris sicuro")
            else -> {
                val text = if (suggestion.danger) "Tris trovato — attenzione al vassoio" else "Tris trovato"
                publish(suggestion.taps.map { it.bounds }, text)
            }
        }
    }

    private fun publish(rects: List<RectF>, message: String) {
        sendBroadcast(
            Intent(OverlayService.ACTION_SUGGESTION)
                .setPackage(packageName)
                .putParcelableArrayListExtra(
                    OverlayService.EXTRA_RECTS,
                    ArrayList(rects.map { RectF(it) })
                )
                .putExtra(OverlayService.EXTRA_MESSAGE, message)
        )
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == image.width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun stopCapture() {
        if (stopping) return
        stopping = true
        stopService(Intent(this, OverlayService::class.java))
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        display = null
        reader?.close()
        reader = null
        val activeProjection = projection
        projection = null
        activeProjection?.stop()
        analysisThread?.quitSafely()
        analysisThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Cattura schermo",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "it.trisassist.START"
        const val ACTION_STOP = "it.trisassist.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1001
    }
}
