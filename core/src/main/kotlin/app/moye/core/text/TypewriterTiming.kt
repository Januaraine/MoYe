package app.moye.core.text

import kotlin.math.roundToLong

object TypewriterTiming {
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 3f
    private const val NORMAL_MILLIS = 40.0

    fun clampSpeed(speed: Float): Float = speed.coerceIn(MIN_SPEED, MAX_SPEED)

    fun millisPerCharacter(speed: Float): Long {
        val clamped = clampSpeed(speed)
        return (NORMAL_MILLIS / clamped).roundToLong().coerceIn(8L, 120L)
    }
}
