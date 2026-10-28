package it.trisassist.vision

import android.graphics.RectF

/**
 * Conserva la disposizione mostrata nell'animazione iniziale. Nessuna immagine
 * viene salvata: restano soltanto tipo, rettangolo e profondità stimata.
 */
class InitialBoardMemory {
    private val remembered = mutableListOf<TileDetection>()
    var ready: Boolean = false
        private set

    fun ingestRevealFrame(detections: List<TileDetection>) {
        detections.filter { it.confidence >= 0.65f }.forEach { candidate ->
            val index = remembered.indexOfFirst {
                it.kind == candidate.kind && overlap(it.bounds, candidate.bounds) > 0.72f
            }
            if (index < 0) remembered += candidate else if (
                candidate.confidence > remembered[index].confidence
            ) remembered[index] = candidate
        }
    }

    fun endReveal(): List<TileDetection> {
        ready = remembered.isNotEmpty()
        return remembered.toList()
    }

    fun markRemoved(bounds: RectF) {
        remembered.removeAll { overlap(it.bounds, bounds) > 0.72f }
    }

    fun snapshot(): List<TileDetection> = remembered.toList()

    fun clear() {
        remembered.clear()
        ready = false
    }

    private fun overlap(a: RectF, b: RectF): Float {
        val intersection = RectF()
        if (!intersection.setIntersect(a, b)) return 0f
        val intersectionArea = intersection.width() * intersection.height()
        val smallerArea = minOf(a.width() * a.height(), b.width() * b.height())
        return if (smallerArea <= 0f) 0f else intersectionArea / smallerArea
    }
}
