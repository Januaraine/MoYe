package app.moye.core.importing

import app.moye.core.model.Delivery
import app.moye.core.model.ImportError
import app.moye.core.model.ParsedBook
import java.io.File

object PdfParser {
    fun parse(file: File, displayName: String?): ParseResult {
        if (!file.isFile || !file.canRead()) return ParseResult.Err(ImportError.UNREADABLE)
        val header = try {
            file.inputStream().use { input ->
                val bytes = ByteArray(5)
                val read = input.read(bytes)
                if (read < 5) return ParseResult.Err(ImportError.CORRUPT)
                bytes
            }
        } catch (_: Exception) {
            return ParseResult.Err(ImportError.UNREADABLE)
        }
        if (!header.decodeToString().startsWith("%PDF-")) return ParseResult.Err(ImportError.CORRUPT)
        if (!containsMarker(file, "%%EOF")) return ParseResult.Err(ImportError.CORRUPT)
        return ParseResult.Ok(
            ParsedBook(
                title = ImportNames.title(displayName, "pdf"),
                author = null,
                text = "",
                chapters = emptyList(),
                declaredWritingMode = null,
                delivery = Delivery.PAGES,
            ),
        )
    }

    private fun containsMarker(file: File, marker: String): Boolean {
        val needle = marker.toByteArray()
        return try {
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var pending = ByteArray(0)
                var found = false
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    val window = pending + buffer.copyOf(read)
                    if (indexOf(window, needle) >= 0) {
                        found = true
                        break
                    }
                    pending = if (window.size > needle.size) {
                        window.copyOfRange(window.size - needle.size + 1, window.size)
                    } else {
                        window
                    }
                }
                found
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        if (data.size < needle.size) return -1
        val last = data.size - needle.size
        for (index in 0..last) {
            var matched = true
            for (offset in needle.indices) {
                if (data[index + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return index
        }
        return -1
    }
}
