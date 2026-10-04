package it.trisassist.capture

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.PointF
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
import it.trisassist.accessibility.TrisAccessibilityService
import it.trisassist.overlay.OverlayService
import it.trisassist.vision.GamePhase
import it.trisassist.vision.GameState
import it.trisassist.vision.ItemKind
import it.trisassist.vision.MovePlanner
import it.trisassist.vision.TileRecognizer

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var analysisThread: HandlerThread? = null
    private var stopping = false
    private var oneTripleArmed = false
    private var autoMode = false
    private var executing = false
    private var executionCooldownUntil = 0L
    private var lastAnalysis = 0L
    private var adaptivePoints = mutableListOf<PointF>()
    private var adaptiveKind: ItemKind? = null
    private var lastTappedPoint: PointF? = null
    private var tapCompletedAt = 0L
    private var waitingForBoardChange = false
    private var trayGuardActive = false
    private var trayGuardStartedAt = 0L
    private var lastTraySignature = ""
    private var trayStableFrames = 0
    private var layerProbeLocked = false
    private var lastObservedTraySize = -1
    private var alpacaPauseActive = false
    private var alpacaChoiceLayout: Set<String> = emptySet()
    private var alpacaChoiceSeenAt = 0L
    private var alpacaResumeLayout: Set<String> = emptySet()
    private var alpacaResumeStableFrames = 0
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
            ACTION_ONE_TRIPLE -> {
                if (!executing) {
                    autoMode = false
                    oneTripleArmed = !oneTripleArmed
                    publishControlState()
                }
            }
            ACTION_AUTO -> {
                if (!executing) {
                    autoMode = !autoMode
                    oneTripleArmed = false
                    publishControlState()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        stopping = false
        oneTripleArmed = false
        autoMode = false
        executing = false
        trayGuardActive = false
        lastTraySignature = ""
        trayStableFrames = 0
        layerProbeLocked = false
        lastObservedTraySize = -1
        alpacaPauseActive = false
        alpacaChoiceLayout = emptySet()
        alpacaChoiceSeenAt = 0L
        alpacaResumeLayout = emptySet()
        alpacaResumeStableFrames = 0
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Tris Assist attivo")
                .setContentText("Premi 1× per eseguire un solo tris")
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
            if (now - lastAnalysis < 50L) {
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
        if (frame.alpacaOverlay) {
            alpacaPauseActive = true
            alpacaChoiceLayout = emptySet()
            alpacaChoiceSeenAt = 0L
            alpacaResumeLayout = emptySet()
            alpacaResumeStableFrames = 0
            oneTripleArmed = false
            adaptivePoints.clear()
            adaptiveKind = null
            lastTappedPoint = null
            waitingForBoardChange = false
            executing = false
            layerProbeLocked = true
            publish(emptyList(), "AUTO in pausa: scegli cosa eliminare")
            publishControlState()
            return
        }
        if (alpacaPauseActive && !handleAlpacaResume(frame.boardLayout)) {
            publish(emptyList(), "AUTO in pausa: scegli cosa eliminare")
            return
        }
        val state = GameState(
            tiles = frame.boardTiles,
            tray = frame.tray,
            order = frame.order,
            observingOtherPlayer = false,
            publicStorageUnlocked = false,
            phase = GamePhase.PLAYING
        )
        if (lastObservedTraySize >= 0 && frame.tray.size <= lastObservedTraySize - 2) {
            layerProbeLocked = false
        }
        lastObservedTraySize = frame.tray.size
        updateTrayGuard(frame.tray)
        if (executing) {
            continueAdaptiveSequence(frame.boardTiles)
            return
        }

        val suggestion = planner.suggest(state)
        val layerHint = frame.layerHint?.takeIf {
            autoMode && !layerProbeLocked &&
                frame.tray.size <= 2 && it.confidence >= 0.84f
        }
        when {
            suggestion == null && layerHint != null -> {
                publish(listOf(layerHint.blockerBounds), "Libero uno strato memorizzato")
                val mayExecute = !trayGuardActive &&
                    System.currentTimeMillis() >= executionCooldownUntil
                val point = PointF(
                    layerHint.blockerBounds.centerX(),
                    layerHint.blockerBounds.centerY()
                )
                if (autoMode && !executing && mayExecute &&
                    !isInsidePublicStorage(point, bitmap)
                ) {
                    layerProbeLocked = true
                    startAdaptiveSequence(listOf(point), ItemKind.UNKNOWN)
                }
            }
            frame.boardTiles.isEmpty() -> {
                publish(emptyList(), "Cerco tessere…")
                registerAutoMiss()
            }
            suggestion == null -> {
                publish(emptyList(), "Nessun tris sicuro")
                registerAutoMiss()
            }
            else -> {
                publish(suggestion.taps.map { it.bounds }, "Tris trovato")
                val mayExecute = !trayGuardActive &&
                    System.currentTimeMillis() >= executionCooldownUntil
                if ((oneTripleArmed || autoMode) && !executing && mayExecute) {
                    val points = suggestion.taps.take(3).map {
                        PointF(it.bounds.centerX(), it.bounds.centerY())
                    }
                    // Defence in depth: even if vision misclassifies the blue
                    // team storage, AUTO refuses every point inside that area.
                    if (autoMode && points.any { isInsidePublicStorage(it, bitmap) }) {
                        publish(emptyList(), "Magazzino pubblico protetto")
                        return
                    }
                    oneTripleArmed = false
                    startAdaptiveSequence(points, suggestion.kind)
                }
            }
        }
    }

    private fun startAdaptiveSequence(points: List<PointF>, kind: ItemKind) {
        adaptivePoints = points.take(3).toMutableList()
        adaptiveKind = kind
        executing = true
        waitingForBoardChange = false
        publishControlState()
        tapNextAdaptive()
    }

    private fun tapNextAdaptive() {
        val point = adaptivePoints.removeFirstOrNull()
        if (point == null) {
            finishAdaptiveSequence()
            return
        }
        lastTappedPoint = point
        val started = TrisAccessibilityService.performTap(point) {
            tapCompletedAt = System.currentTimeMillis()
            waitingForBoardChange = true
        }
        if (!started) finishAdaptiveSequence()
    }

    private fun continueAdaptiveSequence(tiles: List<it.trisassist.vision.TileDetection>) {
        if (!waitingForBoardChange) return
        val point = lastTappedPoint ?: return finishAdaptiveSequence()
        val kind = adaptiveKind ?: return finishAdaptiveSequence()
        val sameTileStillVisible = tiles.any { tile ->
            tile.kind == kind &&
                kotlin.math.abs(tile.bounds.centerX() - point.x) <= tile.bounds.width() * 0.24f &&
                kotlin.math.abs(tile.bounds.centerY() - point.y) <= tile.bounds.height() * 0.24f
        }
        val timedOut = System.currentTimeMillis() - tapCompletedAt >= 145L
        if (!sameTileStillVisible || timedOut) {
            waitingForBoardChange = false
            tapNextAdaptive()
        }
    }

    private fun finishAdaptiveSequence() {
        val completedKind = adaptiveKind
        adaptivePoints.clear()
        adaptiveKind = null
        lastTappedPoint = null
        waitingForBoardChange = false
        executing = false
        executionCooldownUntil = System.currentTimeMillis() + 15L
        trayGuardActive = true
        trayGuardStartedAt = System.currentTimeMillis()
        lastTraySignature = ""
        trayStableFrames = 0
        if (completedKind != null && completedKind != ItemKind.UNKNOWN) {
            layerProbeLocked = false
        }
        publishControlState()
    }

    private fun handleAlpacaResume(current: Set<String>): Boolean {
        if (current.isEmpty()) return false
        val now = System.currentTimeMillis()
        if (alpacaChoiceLayout.isEmpty()) {
            alpacaChoiceLayout = current
            alpacaChoiceSeenAt = now
            return false
        }
        val choiceChange = layoutChange(alpacaChoiceLayout, current)
        if (alpacaResumeLayout.isEmpty()) {
            if (now - alpacaChoiceSeenAt < 120L || choiceChange < 0.35f) return false
            alpacaResumeLayout = current
            alpacaResumeStableFrames = 1
            return false
        }
        if (layoutChange(alpacaResumeLayout, current) <= 0.12f) {
            alpacaResumeStableFrames++
        } else {
            alpacaResumeLayout = current
            alpacaResumeStableFrames = 1
        }
        if (alpacaResumeStableFrames < 3) return false
        alpacaPauseActive = false
        alpacaChoiceLayout = emptySet()
        alpacaResumeLayout = emptySet()
        alpacaResumeStableFrames = 0
        layerProbeLocked = false
        trayGuardActive = true
        trayGuardStartedAt = now
        publish(emptyList(), "AUTO riparte")
        return true
    }

    private fun layoutChange(first: Set<String>, second: Set<String>): Float {
        val union = first union second
        if (union.isEmpty()) return 0f
        return 1f - (first intersect second).size.toFloat() / union.size.toFloat()
    }

    private fun updateTrayGuard(tray: List<ItemKind>) {
        if (!trayGuardActive) return
        val signature = tray.joinToString(",") { it.name }
        if (signature == lastTraySignature) {
            trayStableFrames++
        } else {
            lastTraySignature = signature
            trayStableFrames = 1
        }
        val elapsed = System.currentTimeMillis() - trayGuardStartedAt
        if ((trayStableFrames >= 2 && elapsed >= 45L) || elapsed >= 320L) {
            trayGuardActive = false
        }
    }

    private fun isInsidePublicStorage(point: PointF, bitmap: Bitmap): Boolean {
        val x = point.x / bitmap.width
        val y = point.y / bitmap.height
        return x in 0.50f..0.94f && y in 0.63f..0.78f
    }

    private fun registerAutoMiss() {
        // Keep AUTO armed while animations run or no safe tris is visible.
        // It will wait without touching anything and turns off only when the
        // user long-presses the control again.
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

    private fun publishControlState() {
        sendBroadcast(
            Intent(OverlayService.ACTION_CONTROL_STATE)
                .setPackage(packageName)
                .putExtra(OverlayService.EXTRA_ARMED, oneTripleArmed)
                .putExtra(OverlayService.EXTRA_AUTO, autoMode)
                .putExtra(
                    OverlayService.EXTRA_ACCESSIBILITY_READY,
                    TrisAccessibilityService.isReady()
                )
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
        oneTripleArmed = false
        autoMode = false
        executing = false
        adaptivePoints.clear()
        adaptiveKind = null
        lastTappedPoint = null
        waitingForBoardChange = false
        trayGuardActive = false
        lastTraySignature = ""
        trayStableFrames = 0
        layerProbeLocked = false
        lastObservedTraySize = -1
        alpacaPauseActive = false
        alpacaChoiceLayout = emptySet()
        alpacaChoiceSeenAt = 0L
        alpacaResumeLayout = emptySet()
        alpacaResumeStableFrames = 0
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
        const val ACTION_ONE_TRIPLE = "it.trisassist.ONE_TRIPLE"
        const val ACTION_AUTO = "it.trisassist.AUTO"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1001
    }
}
