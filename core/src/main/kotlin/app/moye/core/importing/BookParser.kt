package app.moye.core.importing

import app.moye.core.model.BookFormat
import app.moye.core.model.ImportError
import app.moye.core.text.ChapterDetector
import java.io.File

object BookParser {
    const val MAX_BYTES = 80L * 1024L * 1024L

    fun detectFormat(fileName: String?, mime: String?): BookFormat? {
        val name = fileName?.lowercase()?.substringAfterLast('/')?.substringAfterLast('\\').orEmpty()
        val type = mime?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        return when {
            name.endsWith(".epub") || type == "application/epub+zip" -> BookFormat.EPUB
            name.endsWith(".txt") || type == "text/plain" -> BookFormat.TXT
            else -> null
        }
    }

    fun parse(format: BookFormat, file: File, displayName: String?): ParseResult {
        if (!file.isFile || !file.canRead()) return ParseResult.Err(ImportError.UNREADABLE)
        return when (format) {
            BookFormat.TXT -> parseTxt(file, displayName)
            BookFormat.EPUB -> EpubParser.parse(file)
        }
    }

    private fun parseTxt(file: File, displayName: String?): ParseResult {
        val bytes = try {
            file.readBytes()
        } catch (_: Exception) {
            return ParseResult.Err(ImportError.UNREADABLE)
        }
        if (bytes.any { it == 0.toByte() }) return ParseResult.Err(ImportError.CORRUPT)
        val text = TxtDecoder.decode(bytes).trim()
        if (text.isEmpty()) return ParseResult.Err(ImportError.EMPTY)
        val title = displayName?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.removeSuffix(".txt")?.removeSuffix(".TXT")?.trim()?.ifBlank { null }
        return ParseResult.Ok(
            app.moye.core.model.ParsedBook(
                title = title,
                author = null,
                text = text,
                chapters = ChapterDetector.detect(text),
                declaredWritingMode = null,
            ),
        )
    }
}
