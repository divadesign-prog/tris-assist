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
    private var alpacaAnimationSeen = false
    private var alpacaAdviceUntil = 0L
    private var alpacaAdviceKind: ItemKind? = null
    private var adaptiveExecutedCount = 0
    private val removedCounts = mutableMapOf<ItemKind, Int>()
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
        alpacaAnimationSeen = false
        alpacaAdviceUntil = 0L
        alpacaAdviceKind = null
        adaptiveExecutedCount = 0
        removedCounts.clear()
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
            if (now - lastAnalysis < 140L) {
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
        if (isAlpacaFrame(bitmap)) {
            if (!alpacaAnimationSeen) {
                alpacaAnimationSeen = true
                alpacaAdviceUntil = 0L
                alpacaAdviceKind = null
                autoMode = false
                oneTripleArmed = false
                publishControlState()
            }
            publish(emptyList(), "Alpache in arrivo")
            return
        }

        val frame = recognizer.recognize(bitmap)
        val state = GameState(
            tiles = frame.boardTiles,
            tray = frame.tray,
            order = frame.order,
            observingOtherPlayer = false,
            publicStorageUnlocked = false,
            phase = GamePhase.PLAYING
        )
        updateTrayGuard(frame.tray)

        if (alpacaAnimationSeen) {
            val recommendation = chooseAlpacaRemoval(state)
            if (recommendation != null) {
                alpacaAdviceKind = recommendation.kind
                alpacaAdviceUntil = System.currentTimeMillis() + 3500L
                alpacaAnimationSeen = false
            } else {
                publish(emptyList(), "Cerco oggetto da togliere…")
                return
            }
        }
        if (System.currentTimeMillis() < alpacaAdviceUntil) {
            val recommended = frame.boardTiles
                .filter { it.selectable && it.kind == alpacaAdviceKind }
                .maxByOrNull { it.confidence }
            if (recommended != null) {
                publish(listOf(recommended.bounds), "TOGLI: ${recommended.kind.label.uppercase()}")
            } else {
                val label = alpacaAdviceKind?.label?.uppercase() ?: "OGGETTO"
                publish(emptyList(), "TOGLI: $label")
            }
            return
        }

        if (executing) {
            continueAdaptiveSequence(frame.boardTiles)
            return
        }

        val suggestion = planner.suggest(state)
        when {
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
        adaptiveExecutedCount = adaptivePoints.size
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
        val timedOut = System.currentTimeMillis() - tapCompletedAt >= 420L
        if (!sameTileStillVisible || timedOut) {
            waitingForBoardChange = false
            tapNextAdaptive()
        }
    }

    private fun finishAdaptiveSequence() {
        adaptiveKind?.let { kind ->
            removedCounts[kind] = (removedCounts[kind] ?: 0) + adaptiveExecutedCount
        }
        adaptiveExecutedCount = 0
        adaptivePoints.clear()
        adaptiveKind = null
        lastTappedPoint = null
        waitingForBoardChange = false
        executing = false
        executionCooldownUntil = System.currentTimeMillis() + 120L
        trayGuardActive = true
        trayGuardStartedAt = System.currentTimeMillis()
        lastTraySignature = ""
        trayStableFrames = 0
        publishControlState()
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
        if ((trayStableFrames >= 2 && elapsed >= 180L) || elapsed >= 900L) {
            trayGuardActive = false
        }
    }

    private fun chooseAlpacaRemoval(state: GameState): it.trisassist.vision.TileDetection? {
        val selectable = state.tiles.filter {
            it.selectable && it.kind != ItemKind.UNKNOWN && it.kind != ItemKind.GEM
        }
        return selectable
            .groupBy { it.kind }
            .filterKeys { it != state.order }
            .maxByOrNull { (kind, foreground) ->
                val totalVisible = state.tiles.count { it.kind == kind }
                val deeper = state.tiles.count { it.kind == kind && it.estimatedLayer > 0 }
                totalVisible * 12 + deeper * 5 + foreground.size * 4 -
                    (removedCounts[kind] ?: 0)
            }
            ?.value
            ?.maxByOrNull { it.confidence }
    }

    private fun isAlpacaFrame(bitmap: Bitmap): Boolean {
        var white = 0
        var sampled = 0
        val startY = (bitmap.height * 0.18f).toInt()
        val endY = (bitmap.height * 0.76f).toInt()
        var y = startY
        while (y < endY) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(color)
                val g = android.graphics.Color.green(color)
                val b = android.graphics.Color.blue(color)
                if (r >= 246 && g >= 246 && b >= 246) white++
                sampled++
                x += 12
            }
            y += 12
        }
        return sampled > 0 && white.toFloat() / sampled >= 0.10f
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
        alpacaAnimationSeen = false
        alpacaAdviceUntil = 0L
        alpacaAdviceKind = null
        adaptiveExecutedCount = 0
        removedCounts.clear()
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
