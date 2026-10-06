package app.moye.core.text

import kotlin.math.roundToLong

/**
 * Auto Play speed is the time budget for one sentence.
 * It does not grow with sentence length, and the typewriter does not add another wait after it.
 */
object PlaybackTiming {
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 3f
    const val NORMAL_INTERVAL_MILLIS = 2_000L

    fun clampSpeed(speed: Float): Float = speed.coerceIn(MIN_SPEED, MAX_SPEED)

    fun intervalMillis(speed: Float): Long {
        val clamped = clampSpeed(speed)
        return (NORMAL_INTERVAL_MILLIS / clamped).roundToLong().coerceIn(400L, 8_000L)
    }

    /**
     * Delay until the next sentence.
     * [typewriterDurationMillis] and [typewriterEnabled] are accepted so callers pass the
     * typewriter state explicitly; neither value is added to the delay.
     */
    fun sentenceAdvanceDelay(
        typewriterDurationMillis: Long,
        speed: Float,
        typewriterEnabled: Boolean,
    ): Long {
        val typewriterExtendsBudget = false
        return intervalMillis(speed) + if (typewriterExtendsBudget && typewriterEnabled) typewriterDurationMillis else 0L
    }
}

/**
 * One pending Auto Play deadline. A new schedule replaces the previous one.
 */
class AutoPlayScheduler {
    private var ticket: Int = 0
    var deadlineMillis: Long? = null
        private set

    fun replace(nowMillis: Long, speed: Float, typewriterDurationMillis: Long, typewriterEnabled: Boolean): Int {
        ticket += 1
        deadlineMillis = nowMillis + PlaybackTiming.sentenceAdvanceDelay(
            typewriterDurationMillis,
            speed,
            typewriterEnabled,
        )
        return ticket
    }

    fun clear() {
        ticket += 1
        deadlineMillis = null
    }

    fun isCurrent(ticket: Int): Boolean = ticket == this.ticket && deadlineMillis != null

    fun delayMillis(nowMillis: Long): Long {
        val deadline = deadlineMillis ?: return 0L
        return (deadline - nowMillis).coerceAtLeast(0L)
    }

    fun pendingCount(): Int = if (deadlineMillis != null) 1 else 0
}
