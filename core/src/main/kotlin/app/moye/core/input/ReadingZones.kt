package app.moye.core.input

enum class ReadingZone {
    PREVIOUS_PAGE,
    CONTROLS,
    ADVANCE,
}

object ReadingZones {
    const val LEFT_EDGE = 0.28f
    const val RIGHT_EDGE = 0.72f

    fun fromX(fraction: Float): ReadingZone {
        val x = fraction.coerceIn(0f, 1f)
        return when {
            x < LEFT_EDGE -> ReadingZone.PREVIOUS_PAGE
            x > RIGHT_EDGE -> ReadingZone.ADVANCE
            else -> ReadingZone.CONTROLS
        }
    }
}
