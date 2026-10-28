package it.trisassist.learning

import android.content.Context
import android.graphics.PointF
import it.trisassist.vision.ItemKind
import it.trisassist.vision.TileDetection
import kotlin.math.abs

class LearningMemory(context: Context) {
    private val prefs = context.getSharedPreferences("auto_tris_learning", Context.MODE_PRIVATE)
    private var previous = emptyList<TileDetection>()
    private val sessionMoves = mutableListOf<Move>()
    private var stableKey = ""
    private var stableFrames = 0
    private var replayIndex = 0

    data class Move(val kind: ItemKind, val x: Float, val y: Float)

    fun startSession() {
        previous = emptyList(); sessionMoves.clear(); stableKey = ""; stableFrames = 0
    }

    fun observe(tiles: List<TileDetection>, tray: List<ItemKind>) {
        val key = tiles.sortedBy { it.bounds.centerY() * 2000f + it.bounds.centerX() }
            .joinToString("|") { "${it.kind.name}:${it.bounds.centerX().toInt()}:${it.bounds.centerY().toInt()}" }
        if (key == stableKey) stableFrames++ else { stableKey = key; stableFrames = 1 }
        if (stableFrames < 2) return
        if (previous.isNotEmpty()) {
            val removed = previous.filter { old ->
                tiles.none { now ->
                    now.kind == old.kind &&
                        abs(now.bounds.centerX() - old.bounds.centerX()) < old.bounds.width() * 0.30f &&
                        abs(now.bounds.centerY() - old.bounds.centerY()) < old.bounds.height() * 0.30f
                }
            }
            if (removed.size == 1) {
                val tile = removed.first()
                val move = Move(tile.kind, tile.bounds.centerX(), tile.bounds.centerY())
                if (sessionMoves.lastOrNull() != move) sessionMoves += move
            }
        }
        previous = tiles.map { it.copy() }
        if (sessionMoves.size >= 3 && sessionMoves.size % 3 == 0) saveSession()
    }

    fun finishSession() {
        if (sessionMoves.size >= 3) saveSession()
        previous = emptyList()
    }

    fun resetReplay() { replayIndex = 0 }

    fun nextReplay(tiles: List<TileDetection>, tray: List<ItemKind>): Pair<ItemKind, List<PointF>>? {
        if (tray.size > 4) return null
        val moves = loadMoves()
        if (moves.size < 3) return null
        if (replayIndex + 3 > moves.size) replayIndex = 0
        val step = moves.subList(replayIndex, replayIndex + 3)
        val kind = step.first().kind
        if (step.any { it.kind != kind }) return null
        val points = step.mapNotNull { learned ->
            tiles.filter { it.kind == learned.kind }
                .minByOrNull { abs(it.bounds.centerX() - learned.x) + abs(it.bounds.centerY() - learned.y) }
                ?.takeIf {
                    abs(it.bounds.centerX() - learned.x) < it.bounds.width() * 0.55f &&
                    abs(it.bounds.centerY() - learned.y) < it.bounds.height() * 0.55f
                }?.let { PointF(it.bounds.centerX(), it.bounds.centerY()) }
        }
        if (points.size != 3 || points.distinctBy { "${it.x.toInt()}:${it.y.toInt()}" }.size != 3) return null
        replayIndex += 3
        return kind to points
    }

    private fun saveSession() {
        prefs.edit().putString("moves", sessionMoves.joinToString(";") {
            "${it.kind.name},${it.x},${it.y}"
        }).apply()
    }

    private fun loadMoves(): List<Move> = prefs.getString("moves", null)
        ?.split(";")?.mapNotNull { row ->
            val p = row.split(",")
            if (p.size != 3) null else runCatching {
                Move(ItemKind.valueOf(p[0]), p[1].toFloat(), p[2].toFloat())
            }.getOrNull()
        } ?: emptyList()
}
