package app.moye.core.text

import app.moye.core.model.TypewriterSpeed

object TypewriterTiming {
    fun millisPerCharacter(speed: TypewriterSpeed): Long = when (speed) {
        TypewriterSpeed.SLOW -> 85L
        TypewriterSpeed.NORMAL -> 40L
        TypewriterSpeed.FAST -> 16L
    }
}
