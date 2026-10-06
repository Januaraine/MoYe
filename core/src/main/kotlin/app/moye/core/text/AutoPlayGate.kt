package app.moye.core.text

/**
 * Auto-play waits on a single token. Manual taps, opening the reader controls,
 * and pausing all replace that token so an older wait cannot continue.
 */
data class AutoPlayGate(
    val token: Int = 0,
    val armed: Boolean = false,
    val controlsOpen: Boolean = false,
) {
    val acceptsSchedule: Boolean get() = armed && !controlsOpen

    fun start(): AutoPlayGate = copy(armed = true, token = token + 1)

    fun pause(): AutoPlayGate = copy(armed = false, token = token + 1)

    fun showControls(): AutoPlayGate {
        if (controlsOpen) return this
        return copy(controlsOpen = true, token = token + 1)
    }

    fun hideControls(): AutoPlayGate {
        if (!controlsOpen) return this
        return copy(controlsOpen = false, token = token + 1)
    }

    fun interrupt(): AutoPlayGate = copy(token = token + 1)

    fun isCurrent(ticket: Int): Boolean = ticket == token && acceptsSchedule
}
