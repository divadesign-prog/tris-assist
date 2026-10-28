package it.trisassist.vision

import android.graphics.RectF

enum class ItemKind(val label: String) {
    GEM("Diamanti"),
    SPROUT("Erba"),
    SOCK("Calze"),
    GLOVE("Guanti"),
    HAT("Cappello"),
    CORN("Mais"),
    PEA("Piselli"),
    CARROT("Carote"),
    WOOL("Nuvola"),
    SKEWER("Spiedo"),
    YARN("Gomitolo"),
    CHEESE("Formaggio"),
    CUTLERY("Posate"),
    MEAT("Carne"),
    CAKE("Torta"),
    MILK("Latte"),
    UNKNOWN("Oggetto")
}

enum class GamePhase { INITIAL_REVEAL, PLAYING, OBSERVING, FINISHED }

data class TileDetection(
    val kind: ItemKind,
    val bounds: RectF,
    val selectable: Boolean,
    val confidence: Float,
    val estimatedLayer: Int = 0
)

data class GameState(
    val tiles: List<TileDetection>,
    val tray: List<ItemKind>,
    val order: ItemKind?,
    val observingOtherPlayer: Boolean,
    val publicStorageUnlocked: Boolean,
    val phase: GamePhase = GamePhase.PLAYING
)

data class MoveSuggestion(
    val taps: List<TileDetection>,
    val kind: ItemKind,
    val reason: String,
    val danger: Boolean
)
