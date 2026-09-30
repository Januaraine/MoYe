package app.moye.core.time

class ReadingTimer {
    private var accumulatedMs: Long = 0
    private var startedAt: Long? = null
    private var accountedMs: Long = 0

    val isRunning: Boolean
        get() = startedAt != null

    fun resume(nowMs: Long) {
        if (startedAt == null) startedAt = nowMs
    }

    fun pause(nowMs: Long) {
        val start = startedAt ?: return
        accumulatedMs += (nowMs - start).coerceAtLeast(0)
        startedAt = null
    }

    fun elapsed(nowMs: Long): Long {
        val running = startedAt?.let { (nowMs - it).coerceAtLeast(0) } ?: 0L
        return accumulatedMs + running
    }

    fun takeDelta(nowMs: Long): Long {
        val total = elapsed(nowMs)
        val delta = (total - accountedMs).coerceAtLeast(0)
        accountedMs = total
        return delta
    }
}

data class DurationParts(
    val hours: Long,
    val minutes: Long,
    val underOneMinute: Boolean,
) {
    companion object {
        fun from(durationMs: Long): DurationParts {
            val safe = durationMs.coerceAtLeast(0)
            val totalMinutes = safe / 60_000L
            if (totalMinutes < 1L) return DurationParts(0, 0, true)
            return DurationParts(totalMinutes / 60L, totalMinutes % 60L, false)
        }
    }
}
