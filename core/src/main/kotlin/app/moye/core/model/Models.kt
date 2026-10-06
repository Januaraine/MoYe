package app.moye.core.model

enum class BookFormat {
    TXT,
    EPUB,
}

enum class WritingMode {
    HORIZONTAL,
    VERTICAL,
}

enum class PageTurnDirection {
    HORIZONTAL,
    VERTICAL,
}

enum class ReaderTheme {
    PAPER,
    LIGHT,
    DARK,
}

enum class ImportError {
    UNSUPPORTED_FORMAT,
    UNREADABLE,
    EMPTY,
    CORRUPT,
    TOO_LARGE,
}

enum class ContentError {
    MISSING,
    CORRUPT,
    EMPTY,
}

data class Chapter(
    val title: String,
    val startOffset: Int,
)

data class ParsedBook(
    val title: String?,
    val author: String?,
    val text: String,
    val chapters: List<Chapter>,
    val declaredWritingMode: WritingMode?,
    val cover: EmbeddedCover? = null,
)

class EmbeddedCover(
    val mediaType: String,
    val bytes: ByteArray,
) {
    val extension: String
        get() = when (mediaType.lowercase().substringBefore(';').trim()) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/jpeg", "image/jpg" -> "jpg"
            else -> "img"
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddedCover) return false
        return mediaType == other.mediaType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * mediaType.hashCode() + bytes.contentHashCode()
}

data class ReadingUnit(
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val paragraphIndex: Int = 0,
    val sentenceIndex: Int = 0,
)

data class TextSpan(
    val start: Int,
    val end: Int,
)

data class VerticalColumn(
    val start: Int,
    val end: Int,
)

data class VerticalPage(
    val start: Int,
    val end: Int,
    val columns: List<VerticalColumn>,
)

enum class ReaderCommand {
    PREVIOUS,
    NEXT,
    TOGGLE_PLAYBACK,
}

enum class RemovalChoice {
    CANCEL,
    KEEP_COPY,
    DELETE_COPY,
}

sealed class RemovalResult {
    data object Cancelled : RemovalResult()

    data object RemovedKeepCopy : RemovalResult()

    data object RemovedDeletedCopy : RemovalResult()

    data object Failed : RemovalResult()

    data object NotFound : RemovalResult()
}

fun effectiveWritingMode(declared: WritingMode?, txtPreference: WritingMode): WritingMode {
    return declared ?: txtPreference
}
