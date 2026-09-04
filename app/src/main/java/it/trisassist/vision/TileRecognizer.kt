package it.trisassist.vision

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class RecognizedFrame(
    val boardTiles: List<TileDetection>,
    val tray: List<ItemKind>,
    val order: ItemKind?
)

class TileRecognizer {
    private data class Feature(val bounds: RectF, val signature: FloatArray, val region: Int)
    private data class Triplet(
        val indices: IntArray,
        val trayCount: Int,
        val unlockScore: Float,
        val visualScore: Float
    )

    fun recognize(source: Bitmap): RecognizedFrame {
        val uniqueBounds = mutableListOf<RectF>()
        findBrightTiles(source)
            .sortedByDescending { it.width() * it.height() }
            .forEach { bounds ->
                if (uniqueBounds.none { overlap(it, bounds) > 0.55f }) uniqueBounds += bounds
            }

        val features = uniqueBounds.mapNotNull { bounds ->
            val centerY = bounds.centerY() / source.height
            val region = when {
                centerY in 0.18f..0.72f -> 0
                centerY in 0.755f..0.835f -> 1
                else -> -1
            }
            if (region < 0) null else Feature(bounds, signature(source, bounds), region)
        }

        val triplet = findBestTriplet(features, source.width.toFloat())
        val chosen = triplet?.indices?.toSet().orEmpty()
        val board = features.mapIndexedNotNull { index, feature ->
            if (feature.region != 0 || index !in chosen) null else TileDetection(
                kind = ItemKind.GEM,
                bounds = feature.bounds,
                selectable = true,
                confidence = 1f
            )
        }

        var fillerIndex = 1
        val fillers = ItemKind.values().filter { it != ItemKind.UNKNOWN && it != ItemKind.GEM }
        val tray = features.mapIndexedNotNull { index, feature ->
            if (feature.region != 1) null
            else if (index in chosen) ItemKind.GEM
            else fillers[(fillerIndex++ - 1) % fillers.size]
        }

        return RecognizedFrame(boardTiles = board, tray = tray, order = null)
    }

    private fun findBestTriplet(features: List<Feature>, screenWidth: Float): Triplet? {
        if (features.size < 3) return null

        val traySize = features.count { it.region == 1 }.coerceAtMost(7)
        val freeSlots = (7 - traySize).coerceAtLeast(0)
        if (freeSlots == 0) return null

        val distances = Array(features.size) { FloatArray(features.size) }
        for (i in features.indices) for (j in i + 1 until features.size) {
            val value = distance(features[i].signature, features[j].signature)
            distances[i][j] = value
            distances[j][i] = value
        }

        var best: Triplet? = null
        for (a in 0 until features.size - 2) {
            for (b in a + 1 until features.size - 1) {
                for (c in b + 1 until features.size) {
                    val indices = intArrayOf(a, b, c)
                    val boardCount = indices.count { features[it].region == 0 }
                    if (boardCount == 0 || boardCount > freeSlots) continue

                    val visualScore = maxOf(distances[a][b], distances[a][c], distances[b][c])
                    if (visualScore > 0.50f) continue

                    val candidate = Triplet(
                        indices = indices,
                        trayCount = 3 - boardCount,
                        unlockScore = unlockScore(indices, features, screenWidth),
                        visualScore = visualScore
                    )
                    if (isBetter(candidate, best)) best = candidate
                }
            }
        }
        return best
    }

    private fun isBetter(candidate: Triplet, current: Triplet?): Boolean {
        if (current == null) return true

        // First finish pairs/singles already in the tray: this frees space fastest.
        if (candidate.trayCount != current.trayCount) {
            return candidate.trayCount > current.trayCount
        }

        // With the same tray benefit, prefer exposed tiles in denser/central areas:
        // removing them is more likely to reveal useful tiles underneath.
        if (abs(candidate.unlockScore - current.unlockScore) > 0.08f) {
            return candidate.unlockScore > current.unlockScore
        }

        // Finally choose the visually safest match.
        return candidate.visualScore < current.visualScore
    }

    private fun unlockScore(indices: IntArray, features: List<Feature>, screenWidth: Float): Float {
        val board = features.filter { it.region == 0 }
        var total = 0f
        var count = 0

        for (index in indices) {
            val selected = features[index]
            if (selected.region != 0) continue
            count++

            val tileWidth = selected.bounds.width().coerceAtLeast(screenWidth * 0.05f)
            val near = board.count { other ->
                other !== selected &&
                    abs(other.bounds.centerX() - selected.bounds.centerX()) < tileWidth * 1.65f &&
                    abs(other.bounds.centerY() - selected.bounds.centerY()) < tileWidth * 1.65f
            }
            val centrality = 1f - (abs(selected.bounds.centerX() - screenWidth / 2f) / (screenWidth / 2f))
                .coerceIn(0f, 1f)
            total += near.coerceAtMost(8) / 8f + centrality * 0.35f
        }
        return if (count == 0) 0f else total / count
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val delta = a[i] - b[i]
            sum += delta * delta
        }
        return sqrt(sum / a.size)
    }

    private fun signature(source: Bitmap, bounds: RectF): FloatArray {
        val insetX = bounds.width() * 0.08f
        val insetY = bounds.height() * 0.08f
        val left = (bounds.left + insetX).toInt().coerceIn(0, source.width - 1)
        val top = (bounds.top + insetY).toInt().coerceIn(0, source.height - 1)
        val right = (bounds.right - insetX).toInt().coerceIn(left + 1, source.width)
        val bottom = (bounds.bottom - insetY).toInt().coerceIn(top + 1, source.height)
        val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        val scaled = Bitmap.createScaledBitmap(crop, 20, 20, true)
        crop.recycle()
        val pixels = IntArray(400)
        scaled.getPixels(pixels, 0, 20, 0, 0, 20, 20)
        scaled.recycle()

        val values = FloatArray(400 * 3)
        val means = FloatArray(3)
        pixels.forEachIndexed { index, color ->
            val channels = intArrayOf((color shr 16) and 255, (color shr 8) and 255, color and 255)
            for (channel in 0..2) {
                values[index * 3 + channel] = channels[channel].toFloat()
                means[channel] += channels[channel]
            }
        }
        for (channel in 0..2) means[channel] /= 400f
        val deviations = FloatArray(3)
        for (i in 0 until 400) for (channel in 0..2) {
            val d = values[i * 3 + channel] - means[channel]
            deviations[channel] += d * d
        }
        for (channel in 0..2) deviations[channel] = max(12f, sqrt(deviations[channel] / 400f))
        for (i in 0 until 400) for (channel in 0..2) {
            values[i * 3 + channel] = (values[i * 3 + channel] - means[channel]) / deviations[channel]
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
                    if (abs(nx - x) + abs(ny - y) != 1) continue
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

    private fun overlap(a: RectF, b: RectF): Float {
        val intersection = RectF()
        if (!intersection.setIntersect(a, b)) return 0f
        val area = intersection.width() * intersection.height()
        val smaller = minOf(a.width() * a.height(), b.width() * b.height())
        return if (smaller <= 0f) 0f else area / smaller
    }
}
