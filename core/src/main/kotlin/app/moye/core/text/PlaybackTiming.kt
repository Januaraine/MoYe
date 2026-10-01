package app.moye.core.text

import kotlin.math.roundToLong

object PlaybackTiming {
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 3f

    fun clampSpeed(speed: Float): Float = speed.coerceIn(MIN_SPEED, MAX_SPEED)

    fun dwellMillis(text: String, speed: Float): Long {
        val clamped = clampSpeed(speed)
        val weight = text.count { !it.isWhitespace() }.coerceAtLeast(1)
        val base = 450.0 + weight * 140.0
        return (base / clamped).roundToLong().coerceIn(350L, 60_000L)
    }
}
