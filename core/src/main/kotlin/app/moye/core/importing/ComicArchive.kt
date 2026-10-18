package app.moye.core.importing

import app.moye.core.model.BookFormat
import app.moye.core.model.Delivery
import app.moye.core.model.EmbeddedCover
import app.moye.core.model.ImportError
import app.moye.core.model.ParsedBook
import app.moye.core.model.storageExtension
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

object ComicArchive {
    private const val MAX_PAGE_BYTES = 40 * 1024 * 1024
    private const val MAX_COVER_BYTES = 8 * 1024 * 1024
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

    fun parse(file: File, format: BookFormat, displayName: String?): ParseResult {
        val pages = try {
            pageNames(file, format)
        } catch (_: Exception) {
            return ParseResult.Err(ImportError.CORRUPT)
        }
        if (pages.isEmpty()) return ParseResult.Err(ImportError.EMPTY)
        val extension = format.storageExtension()
        return ParseResult.Ok(
            ParsedBook(
                title = ImportNames.title(displayName, extension),
                author = null,
                text = "",
                chapters = emptyList(),
                declaredWritingMode = null,
                cover = cover(file, format, pages.first()),
                delivery = Delivery.PAGES,
                pageCount = pages.size,
            ),
        )
    }

    fun read(file: File, format: BookFormat, index: Int): ByteArray? {
        val pages = try {
            pageNames(file, format)
        } catch (_: Exception) {
            return null
        }
        val name = pages.getOrNull(index) ?: return null
        return try {
            readNamed(file, format, name)
        } catch (_: Exception) {
            null
        }
    }

    private fun cover(file: File, format: BookFormat, name: String): EmbeddedCover? {
        val bytes = try {
            readNamed(file, format, name)
        } catch (_: Exception) {
            null
        } ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return null
        return EmbeddedCover(mediaType(name), bytes)
    }

    private fun pageNames(file: File, format: BookFormat): List<String> {
        val names = when (format) {
            BookFormat.CBZ -> zipNames(file)
            BookFormat.CBR -> rarNames(file)
            else -> emptyList()
        }
        return names.filter { isImage(it) }.sortedWith(::compareNatural)
    }

    private fun readNamed(file: File, format: BookFormat, name: String): ByteArray? {
        val bytes = when (format) {
            BookFormat.CBZ -> readZip(file, name)
            BookFormat.CBR -> readRar(file, name)
            else -> null
        } ?: return null
        if (bytes.size > MAX_PAGE_BYTES) return null
        return bytes
    }

    private fun zipNames(file: File): List<String> {
        ZipFile(file).use { zip ->
            return zip.entries().asSequence().map { it.name }.toList()
        }
    }

    private fun readZip(file: File, name: String): ByteArray? {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(name) ?: return null
            if (entry.size > MAX_PAGE_BYTES) return null
            return zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    private fun rarNames(file: File): List<String> {
        if (!hasRarMagic(file)) throw IllegalArgumentException("not a rar")
        Archive(file).use { archive ->
            return fileHeaders(archive).map { it.fileName }
        }
    }

    private fun hasRarMagic(file: File): Boolean {
        val bytes = ByteArray(8)
        val read = try {
            file.inputStream().use { it.read(bytes) }
        } catch (_: Exception) {
            return false
        }
        if (read < 7) return false
        return bytes[0] == 0x52.toByte() &&
            bytes[1] == 0x61.toByte() &&
            bytes[2] == 0x72.toByte() &&
            bytes[3] == 0x21.toByte() &&
            bytes[4] == 0x1A.toByte() &&
            bytes[5] == 0x07.toByte()
    }

    private fun readRar(file: File, name: String): ByteArray? {
        Archive(file).use { archive ->
            val header = fileHeaders(archive).firstOrNull { it.fileName == name } ?: return null
            return readHeader(archive, header)
        }
    }

    private fun fileHeaders(archive: Archive): List<FileHeader> {
        val headers = archive.fileHeaders ?: return emptyList()
        return headers.mapNotNull { header -> header as? FileHeader }
    }

    private fun readHeader(archive: Archive, header: FileHeader): ByteArray? {
        if (header.isDirectory) return null
        val output = ByteArrayOutputStream()
        archive.extractFile(header, output)
        return output.toByteArray()
    }

    private fun isImage(name: String): Boolean {
        val normalized = name.replace('\\', '/')
        if (normalized.endsWith("/")) return false
        if ("__MACOSX/" in normalized || "/__MACOSX/" in normalized || normalized.startsWith("__MACOSX/")) return false
        val base = normalized.substringAfterLast('/')
        if (base.isEmpty() || base.startsWith(".")) return false
        return base.substringAfterLast('.', "").lowercase() in imageExtensions
    }

    private fun mediaType(name: String): String {
        return when (name.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> "image/jpeg"
        }
    }

    private fun compareNatural(left: String, right: String): Int {
        val leftParts = naturalParts(left)
        val rightParts = naturalParts(right)
        val count = minOf(leftParts.size, rightParts.size)
        for (index in 0 until count) {
            val a = leftParts[index]
            val b = rightParts[index]
            val compared = if (a is Int && b is Int) a.compareTo(b) else a.toString().compareTo(b.toString())
            if (compared != 0) return compared
        }
        return leftParts.size.compareTo(rightParts.size)
    }

    private fun naturalParts(value: String): List<Any> {
        val parts = mutableListOf<Any>()
        val pattern = Regex("""\d+|\D+""")
        pattern.findAll(value.replace('\\', '/').lowercase()).forEach { match ->
            val token = match.value
            parts += token.toIntOrNull() ?: token
        }
        return parts
    }
}
