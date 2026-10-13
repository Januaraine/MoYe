package app.moye.core.importing

import app.moye.core.model.BookFormat
import app.moye.core.model.ImportError
import app.moye.core.text.ChapterDetector
import java.io.File

object BookParser {
    const val MAX_BYTES = 200L * 1024L * 1024L

    fun detectFormat(fileName: String?, mime: String?): BookFormat? {
        val name = fileName?.lowercase()?.substringAfterLast('/')?.substringAfterLast('\\').orEmpty()
        val type = mime?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        val byName = when {
            name.endsWith(".epub") -> BookFormat.EPUB
            name.endsWith(".pdf") -> BookFormat.PDF
            name.endsWith(".cbz") -> BookFormat.CBZ
            name.endsWith(".cbr") -> BookFormat.CBR
            name.endsWith(".azw4") -> BookFormat.AZW4
            name.endsWith(".azw") -> BookFormat.AZW
            name.endsWith(".txt") -> BookFormat.TXT
            else -> null
        }
        if (byName != null) return byName
        return when (type) {
            "application/epub+zip" -> BookFormat.EPUB
            "application/pdf" -> BookFormat.PDF
            "application/vnd.comicbook+zip", "application/x-cbz" -> BookFormat.CBZ
            "application/vnd.comicbook-rar",
            "application/x-cbr",
            "application/vnd.rar",
            "application/x-rar-compressed",
            -> BookFormat.CBR
            "application/vnd.amazon.ebook", "application/x-mobipocket-ebook" -> BookFormat.AZW
            "text/plain" -> BookFormat.TXT
            else -> null
        }
    }

    fun parse(format: BookFormat, file: File, displayName: String?): ParseResult {
        if (!file.isFile || !file.canRead()) return ParseResult.Err(ImportError.UNREADABLE)
        return when (format) {
            BookFormat.TXT -> parseTxt(file, displayName)
            BookFormat.EPUB -> EpubParser.parse(file)
            BookFormat.PDF -> PdfParser.parse(file, displayName)
            BookFormat.CBZ, BookFormat.CBR -> ComicArchive.parse(file, format, displayName)
            BookFormat.AZW -> AzwParser.parseText(file, displayName)
            BookFormat.AZW4 -> AzwParser.parseReplica(file, displayName)
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
