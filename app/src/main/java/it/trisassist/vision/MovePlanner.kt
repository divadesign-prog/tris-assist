package it.trisassist.vision

class MovePlanner {
    fun suggest(state: GameState): MoveSuggestion? {
        if (state.observingOtherPlayer || state.phase != GamePhase.PLAYING) return null
        val freeSlots = 7 - state.tray.size
        val trayCounts = state.tray.groupingBy { it }.eachCount()
        val visible = state.tiles.filter { it.selectable && it.confidence >= 0.30f }
        val candidates = visible.groupBy { it.kind }.mapNotNull { (kind, tiles) ->
            val needed = (3 - (trayCounts[kind] ?: 0)).coerceIn(1, 3)
            if (tiles.size >= needed) {
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
