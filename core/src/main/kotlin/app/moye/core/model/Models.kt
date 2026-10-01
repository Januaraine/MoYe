package app.moye.core.model

enum class BookFormat {
    TXT,
    EPUB,
}

enum class WritingMode {
    HORIZONTAL,
    VERTICAL,
}

enum class ReadingMode {
    PAGED,
    SENTENCE,
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
)

data class ReadingUnit(
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
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
