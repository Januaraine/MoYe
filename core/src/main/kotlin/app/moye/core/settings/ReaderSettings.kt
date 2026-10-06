package app.moye.core.settings

import app.moye.core.model.PageTurnDirection
import app.moye.core.model.ReaderTheme
import app.moye.core.model.WritingMode
import app.moye.core.text.PlaybackTiming
import app.moye.core.text.TypewriterTiming
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ReaderSettings(
    val fontSizeSp: Float = 18f,
    val letterSpacingEm: Float = 0.02f,
    val lineHeight: Float = 1.55f,
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val playbackSpeed: Float = 1f,
    val typewriterEnabled: Boolean = true,
    val typewriterSpeed: Float = 1f,
    val pageTurnDirection: PageTurnDirection = PageTurnDirection.HORIZONTAL,
    val txtWritingMode: WritingMode = WritingMode.HORIZONTAL,
    val languageTag: String = "",
) {
    fun sanitized(): ReaderSettings = copy(
        fontSizeSp = fontSizeSp.coerceIn(14f, 36f),
        letterSpacingEm = letterSpacingEm.coerceIn(0f, 0.3f),
        lineHeight = lineHeight.coerceIn(1.1f, 2.2f),
        playbackSpeed = PlaybackTiming.clampSpeed(playbackSpeed),
        typewriterSpeed = TypewriterTiming.clampSpeed(typewriterSpeed),
        languageTag = when (languageTag) {
            "zh", "en", "" -> languageTag
            else -> ""
        },
    )
}

class FileSettingsStore(private val file: File) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): ReaderSettings = synchronized(file) {
        if (!file.exists()) return ReaderSettings()
        val text = migrateTypewriterSpeed(file.readText())
        if (text.isBlank()) return ReaderSettings()
        return try {
            json.decodeFromString(ReaderSettings.serializer(), text).sanitized()
        } catch (_: Exception) {
            ReaderSettings()
        }
    }

    fun save(settings: ReaderSettings) = synchronized(file) {
        val sanitized = settings.sanitized()
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(sanitized))
        if (file.exists() && !file.delete()) {
            file.writeText(tmp.readText())
            tmp.delete()
            return
        }
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun update(transform: (ReaderSettings) -> ReaderSettings): ReaderSettings {
        val next = transform(load()).sanitized()
        save(next)
        return next
    }
}

internal fun migrateTypewriterSpeed(json: String): String {
    val preset = Regex(""""typewriterSpeed"\s*:\s*"(SLOW|NORMAL|FAST)"""")
    return preset.replace(json) { match ->
        val speed = when (match.groupValues[1]) {
            "SLOW" -> "0.5"
            "FAST" -> "2.5"
            else -> "1.0"
        }
        """"typewriterSpeed": $speed"""
    }
}
