package it.trisassist.vision

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.sqrt

data class RecognizedFrame(
    val boardTiles: List<TileDetection>,
    val tray: List<ItemKind>,
    val order: ItemKind?
)

class TileRecognizer {
    private val templates = TemplateData.load().mapValues { signature(it.value) }

    fun recognize(source: Bitmap): RecognizedFrame {
        val candidates = findBrightTiles(source)
        val board = mutableListOf<TileDetection>()
        val tray = mutableListOf<ItemKind>()

        for (bounds in candidates) {
            val result = classify(source, bounds) ?: continue
            val centerY = bounds.centerY() / source.height
            when {
                centerY in 0.18f..0.72f -> board += TileDetection(
                    kind = result.first,
                    bounds = bounds,
                    selectable = true,
                    confidence = result.second
                )
                centerY in 0.73f..0.88f -> tray += result.first
            }
        }

        val orderSide = (source.width * 0.09f).toInt().coerceAtLeast(64)
        val orderCx = (source.width * 0.835f).toInt()
        val orderCy = (source.height * 0.13f).toInt()
        val orderRect = RectF(
            (orderCx - orderSide / 2).toFloat(),
            (orderCy - orderSide / 2).toFloat(),
            (orderCx + orderSide / 2).toFloat(),
            (orderCy + orderSide / 2).toFloat()
        )
        val order = classify(source, orderRect, 1.05f)?.first

        return RecognizedFrame(
            boardTiles = deduplicate(board),
            tray = tray,
            order = order
        )
    }

    private fun classify(
        source: Bitmap,
        bounds: RectF,
        maxDistance: Float = 0.92f
    ): Pair<ItemKind, Float>? {
        val left = bounds.left.toInt().coerceIn(0, source.width - 1)
        val top = bounds.top.toInt().coerceIn(0, source.height - 1)
        val right = bounds.right.toInt().coerceIn(left + 1, source.width)
        val bottom = bounds.bottom.toInt().coerceIn(top + 1, source.height)
        val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        val sig = signature(crop)
        if (crop !== source) crop.recycle()

        var bestKind = ItemKind.UNKNOWN
        var bestDistance = Float.MAX_VALUE
        var secondBestDistance = Float.MAX_VALUE
        templates.forEach { (kind, template) ->
            var sum = 0f
            for (i in sig.indices) {
                val delta = sig[i] - template[i]
                sum += delta * delta
            }
            val distance = sqrt(sum / sig.size)
            if (distance < bestDistance) {
                secondBestDistance = bestDistance
                bestDistance = distance
                bestKind = kind
            } else if (distance < secondBestDistance) {
                secondBestDistance = distance
            }
        }
        if (bestDistance > maxDistance || secondBestDistance - bestDistance < 0.10f) return null
        return bestKind to (1f - bestDistance / maxDistance).coerceIn(0f, 1f)
    }

    private fun signature(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
        val pixels = IntArray(256)
        scaled.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        if (scaled !== bitmap) scaled.recycle()

        val values = FloatArray(256 * 3)
        val means = FloatArray(3)
        pixels.forEachIndexed { index, color ->
            val r = ((color shr 16) and 255).toFloat()
            val g = ((color shr 8) and 255).toFloat()
            val b = (color and 255).toFloat()
            values[index * 3] = r
            values[index * 3 + 1] = g
            values[index * 3 + 2] = b
            means[0] += r
            means[1] += g
            means[2] += b
        }
        for (c in 0..2) means[c] /= 256f

        val deviations = FloatArray(3)
        for (i in 0 until 256) for (c in 0..2) {
            val d = values[i * 3 + c] - means[c]
            deviations[c] += d * d
        }
        for (c in 0..2) deviations[c] = max(12f, sqrt(deviations[c] / 256f))
        for (i in 0 until 256) for (c in 0..2) {
            values[i * 3 + c] = (values[i * 3 + c] - means[c]) / deviations[c]
        }
        return values
    }

    private fun findBrightTiles(bitmap: Bitmap): List<RectF> {
        val stride = 3
        val cols = bitmap.width / stride
        val rows = bitmap.height / stride
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val mask = BooleanArray(cols * rows)
        val visited = BooleanArray(cols * rows)

        val minY = (rows * 0.16f).toInt()
        val maxY = (rows * 0.89f).toInt()
        for (y in minY until maxY) for (x in 0 until cols) {
            val color = pixels[(y * stride) * bitmap.width + x * stride]
            val r = (color shr 16) and 255
            val g = (color shr 8) and 255
            val b = color and 255
            mask[y * cols + x] = r > 212 && g > 218 && b > 160
        }

        val queue = IntArray(cols * rows)
        val result = mutableListOf<RectF>()
        val minSide = bitmap.width * 0.052f
        val maxSide = bitmap.width * 0.145f

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minX = cols
            var maxX = 0
            var minRow = rows
            var maxRow = 0
            var count = 0

            while (head < tail) {
                val current = queue[head++]
                val x = current % cols
                val y = current / cols
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minRow) minRow = y
                if (y > maxRow) maxRow = y
                val neighbors = intArrayOf(current - 1, current + 1, current - cols, current + cols)
                for (next in neighbors) {
                    if (next !in mask.indices || visited[next] || !mask[next]) continue
                    val nx = next % cols
                    val ny = next / cols
                    if (kotlin.math.abs(nx - x) + kotlin.math.abs(ny - y) != 1) continue
                    visited[next] = true
                    queue[tail++] = next
                }
            }

            val width = (maxX - minX + 1) * stride.toFloat()
            val height = (maxRow - minRow + 1) * stride.toFloat()
            val aspect = width / height.coerceAtLeast(1f)
            val enoughPixels = count * stride * stride > width * height * 0.22f
            if (width in minSide..maxSide && height in minSide..maxSide &&
                aspect in 0.72f..1.38f && enoughPixels
            ) {
                val pad = width * 0.04f
                result += RectF(
                    (minX * stride - pad).coerceAtLeast(0f),
                    (minRow * stride - pad).coerceAtLeast(0f),
                    ((maxX + 1) * stride + pad).coerceAtMost(bitmap.width.toFloat()),
                    ((maxRow + 1) * stride + pad).coerceAtMost(bitmap.height.toFloat())
                )
            }
        }
        return result
    }

    private fun deduplicate(items: List<TileDetection>): List<TileDetection> {
        val sorted = items.sortedByDescending { it.confidence }
        val kept = mutableListOf<TileDetection>()
        for (item in sorted) {
            if (kept.none { overlap(it.bounds, item.bounds) > 0.55f }) kept += item
        }
        return kept
    }

    private fun overlap(a: RectF, b: RectF): Float {
        val intersection = RectF()
        if (!intersection.setIntersect(a, b)) return 0f
        val area = intersection.width() * intersection.height()
        val smaller = minOf(a.width() * a.height(), b.width() * b.height())
        return if (smaller <= 0f) 0f else area / smaller
    }
}
