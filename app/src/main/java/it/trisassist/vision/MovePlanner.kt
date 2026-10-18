package it.trisassist.vision

import android.graphics.RectF

class MovePlanner {
    fun suggest(state: GameState): MoveSuggestion? {
        if (state.observingOtherPlayer || state.phase != GamePhase.PLAYING) return null

        val freeSlots = 7 - state.tray.size
        // Conserva sempre uno spazio libero anche durante le animazioni.
        val safeSlots = (freeSlots - 1).coerceAtLeast(0)
        val trayCounts = state.tray.groupingBy { it }.eachCount()
        val visible = state.tiles.filter {
            it.selectable && it.kind != ItemKind.UNKNOWN && it.confidence >= 0.30f
        }

        // Prima scelta: un tris certo. L'ordine resta prioritario, ma non
        // scavalca mai la protezione del vassoio.
        val triples = visible.groupBy { it.kind }.mapNotNull { (kind, tiles) ->
            val needed = (3 - (trayCounts[kind] ?: 0)).coerceIn(1, 3)
            if (tiles.size >= needed && needed <= safeSlots) {
                val orderBonus = if (kind == state.order) 100 else 0
                val completionBonus = (3 - needed) * 25
                Triple(orderBonus + completionBonus, kind, tiles.take(needed))
            } else null
        }
        val bestTriple = triples.maxByOrNull { it.first }
        if (bestTriple != null) {
            return MoveSuggestion(
                taps = bestTriple.third,
                kind = bestTriple.second,
                reason = if (bestTriple.second == state.order) {
                    "Completa il tris dell'ordine"
                } else {
                    "Completa un tris sicuro"
                },
                danger = freeSlots <= 2
            )
        }

        // Seconda scelta: avanza di una sola tessera lungo una catena.
        // Sopra tre tessere nel vassoio non esplora: cerca soltanto tris.
        if (state.tray.size > 3 || safeSlots < 1) return null

        val incompleteKinds = trayCounts.filterValues { it % 3 != 0 }.keys
        val pathCandidates = visible
            .filter { it.confidence >= 0.58f }
            .filter { tile ->
                val alreadyInTray = (trayCounts[tile.kind] ?: 0) > 0
                alreadyInTray || incompleteKinds.size < 2
            }
            .map { tile ->
                val inTray = trayCounts[tile.kind] ?: 0
                val unlocks = state.tiles.count { covered ->
                    !covered.selectable &&
                        covered.kind != ItemKind.UNKNOWN &&
                        overlap(tile.bounds, covered.bounds) >= 0.14f
                }
                val trayBonus = inTray * 80
                val orderBonus = if (tile.kind == state.order) 45 else 0
                val unlockBonus = unlocks * 18
                val confidenceBonus = (tile.confidence * 10).toInt()
                PathChoice(
                    score = trayBonus + orderBonus + unlockBonus + confidenceBonus,
                    tile = tile,
                    unlocks = unlocks
                )
            }

        val bestPath = pathCandidates.maxByOrNull { it.score } ?: return null
        return MoveSuggestion(
            taps = listOf(bestPath.tile),
            kind = bestPath.tile.kind,
            reason = when {
                (trayCounts[bestPath.tile.kind] ?: 0) > 0 ->
                    "Continua una famiglia già nel vassoio"
                bestPath.unlocks >= 2 ->
                    "Apre una catena e libera più tessere"
                bestPath.tile.kind == state.order ->
                    "Avanza nell'ordine senza rischiare il vassoio"
                else ->
                    "Apre una catena sicura"
            },
            danger = false
        )
    }

    private fun overlap(a: RectF, b: RectF): Float {
        val intersection = RectF()
        if (!intersection.setIntersect(a, b)) return 0f
        val area = intersection.width() * intersection.height()
        val smaller = minOf(a.width() * a.height(), b.width() * b.height())
        return if (smaller <= 0f) 0f else area / smaller
    }

    private data class PathChoice(
        val score: Int,
        val tile: TileDetection,
        val unlocks: Int
    )
}
