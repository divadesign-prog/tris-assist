package it.trisassist.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class TrisAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private fun tapSequence(points: List<PointF>, index: Int = 0, finished: () -> Unit) {
        if (index >= points.size) {
            finished()
            return
        }
        val point = points[index]
        val path = Path().apply { moveTo(point.x, point.y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 35))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.postDelayed({ tapSequence(points, index + 1, finished) }, 130L)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                finished()
            }
        }, null)
    }

    companion object {
        @Volatile private var instance: TrisAccessibilityService? = null
        fun isReady(): Boolean = instance != null
        fun performOneTriple(points: List<PointF>, finished: () -> Unit): Boolean {
            val service = instance ?: return false
            service.handler.post { service.tapSequence(points.take(3), finished = finished) }
            return true
        }
    }
}
