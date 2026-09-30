package app.moye.core.input

import app.moye.core.model.ReaderCommand

/**
 * Android KeyEvent codes. Kept as literals so mapping can be tested without the Android SDK.
 * Unlisted codes return null and must not change the reading position.
 */
object KeyMapper {
    private const val DPAD_UP = 19
    private const val DPAD_DOWN = 20
    private const val DPAD_LEFT = 21
    private const val DPAD_RIGHT = 22
    private const val VOLUME_UP = 24
    private const val VOLUME_DOWN = 25
    private const val KEY_P = 44
    private const val SPACE = 62
    private const val MEDIA_PLAY_PAUSE = 85
    private const val MEDIA_NEXT = 87
    private const val MEDIA_PREVIOUS = 88
    private const val MEDIA_REWIND = 89
    private const val MEDIA_FAST_FORWARD = 90
    private const val PAGE_UP = 92
    private const val PAGE_DOWN = 93
    private const val BUTTON_A = 96
    private const val BUTTON_L1 = 102
    private const val BUTTON_R1 = 103

    fun map(keyCode: Int): ReaderCommand? = when (keyCode) {
        PAGE_UP, DPAD_LEFT, DPAD_UP, VOLUME_UP, MEDIA_PREVIOUS, MEDIA_REWIND, BUTTON_L1 ->
            ReaderCommand.PREVIOUS
        PAGE_DOWN, DPAD_RIGHT, DPAD_DOWN, VOLUME_DOWN, MEDIA_NEXT, MEDIA_FAST_FORWARD, BUTTON_R1 ->
            ReaderCommand.NEXT
        MEDIA_PLAY_PAUSE, SPACE, BUTTON_A, KEY_P ->
            ReaderCommand.TOGGLE_PLAYBACK
        else -> null
    }
}
