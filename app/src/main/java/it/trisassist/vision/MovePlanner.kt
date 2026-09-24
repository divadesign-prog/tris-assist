package it.trisassist.vision

class MovePlanner {
    fun suggestDig(state: GameState): MoveSuggestion? {
        if (state.observingOtherPlayer || state.phase != GamePhase.PLAYING) return null
        // Lo scavo è consentito soltanto con molto margine nel vassoio.
        if (state.tray.size > 3) return null
        val trayCounts = state.tray.groupingBy { it }.eachCount()
        val selectable = state.tiles.filter {
            it.selectable && it.confidence >= 0.45f && it.kind != ItemKind.UNKNOWN
        }
        val hidden = state.tiles.filter { !it.selectable && it.confidence >= 0.35f }
        val best = selectable.maxByOrNull { blocker ->
            val covered = hidden.filter { below ->
                kotlin.math.abs(below.bounds.centerX() - blocker.bounds.centerX()) <=
                    blocker.bounds.width() * 0.72f &&
                kotlin.math.abs(below.bounds.centerY() - blocker.bounds.centerY()) <=
                    blocker.bounds.height() * 0.72f
            }
            val revealsOrder = covered.count { it.kind == state.order }
            val revealsPair = covered.count { (trayCounts[it.kind] ?: 0) >= 2 }
            val revealsMate = covered.count { (trayCounts[it.kind] ?: 0) >= 1 }
            val blockerFitsTray = trayCounts[blocker.kind] ?: 0
            revealsOrder * 160 + revealsPair * 120 + revealsMate * 55 +
                blockerFitsTray * 35 + covered.size * 8 +
                (blocker.confidence * 10).toInt()
        } ?: return null

        val usefulUnder = hidden.any { below ->
            kotlin.math.abs(below.bounds.centerX() - best.bounds.centerX()) <=
                best.bounds.width() * 0.72f &&
            kotlin.math.abs(below.bounds.centerY() - best.bounds.centerY()) <=
                best.bounds.height() * 0.72f &&
            (below.kind == state.order || (trayCounts[below.kind] ?: 0) >= 1)
        }
        val blockerSafe = (trayCounts[best.kind] ?: 0) >= 1
        if (!usefulUnder && !blockerSafe) return null

        return MoveSuggestion(
            taps = listOf(best),
            kind = best.kind,
            reason = "Libera uno strato utile",
            danger = false
        )
    }

    fun suggest(state: GameState): MoveSuggestion? {
        if (state.observingOtherPlayer || state.phase != GamePhase.PLAYING) return null
        val freeSlots = 7 - state.tray.size
        // AUTO conserva sempre almeno uno spazio: evita di arrivare a sette
        // durante l'animazione, anche quando il tris dovrebbe poi sparire.
        val safeSlots = (freeSlots - 1).coerceAtLeast(0)
        val trayCounts = state.tray.groupingBy { it }.eachCount()
        val visible = state.tiles.filter { it.selectable && it.confidence >= 0.30f }
        val candidates = visible.groupBy { it.kind }.mapNotNull { (kind, tiles) ->
            val needed = (3 - (trayCounts[kind] ?: 0)).coerceIn(1, 3)
            if (tiles.size >= needed && needed <= safeSlots) {
                val orderBonus = if (kind == state.order) 100 else 0
                val completionBonus = (3 - needed) * 25
                Triple(orderBonus + completionBonus, kind, tiles.take(needed))
            } else null
        }
        val best = candidates.maxByOrNull { it.first } ?: return null
        return MoveSuggestion(
            taps = best.third,
            kind = best.second,
            reason = if (best.second == state.order) "Completa il tris dell'ordine" else "Completa un tris sicuro",
            danger = freeSlots <= 2
        )
    }
}
