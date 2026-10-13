package app.moye.core.importing

import app.moye.core.model.Delivery
import app.moye.core.model.EmbeddedCover
import app.moye.core.model.ImportError
import app.moye.core.model.ParsedBook
import app.moye.core.text.ChapterDetector
import java.io.File
import java.nio.charset.Charset

object AzwParser {
    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_PALMDOC = 2
    private const val MAX_RECORD_OUTPUT = 16 * 1024
    private const val MAX_COVER_BYTES = 8 * 1024 * 1024

    fun parseText(file: File, displayName: String?): ParseResult {
        val bytes = read(file) ?: return ParseResult.Err(ImportError.UNREADABLE)
        val records = palmRecords(bytes) ?: return ParseResult.Err(ImportError.CORRUPT)
        if (records.isEmpty()) return ParseResult.Err(ImportError.CORRUPT)
        val header = readHeader(records[0]) ?: return ParseResult.Err(ImportError.CORRUPT)
        if (header.encryption != 0) return ParseResult.Err(ImportError.PROTECTED)
        if (header.compression != COMPRESSION_NONE && header.compression != COMPRESSION_PALMDOC) {
            return ParseResult.Err(ImportError.CORRUPT)
        }
        val html = decodeText(records, header) ?: return ParseResult.Err(ImportError.CORRUPT)
        val extracted = HtmlText.extract(html)
        val text = extracted.text.trim()
        if (text.isEmpty()) return ParseResult.Err(ImportError.EMPTY)
        val title = header.title ?: ImportNames.title(displayName, "azw")
        return ParseResult.Ok(
            ParsedBook(
                title = title,
                author = header.author,
                text = text,
                chapters = ChapterDetector.detect(text),
                declaredWritingMode = null,
                cover = coverImage(records, header),
                delivery = Delivery.TEXT,
            ),
        )
    }

    fun parseReplica(file: File, displayName: String?): ParseResult {
        val bytes = read(file) ?: return ParseResult.Err(ImportError.UNREADABLE)
        if (pdfSlice(bytes) == null) return ParseResult.Err(ImportError.CORRUPT)
        val records = palmRecords(bytes)
        val header = records?.firstOrNull()?.let { readHeader(it) }
        if (header != null && header.encryption != 0) return ParseResult.Err(ImportError.PROTECTED)
        return ParseResult.Ok(
            ParsedBook(
                title = header?.title ?: ImportNames.title(displayName, "azw4"),
                author = header?.author,
                text = "",
                chapters = emptyList(),
                declaredWritingMode = null,
                cover = null,
                delivery = Delivery.PAGES,
            ),
        )
    }

    fun extractPdf(file: File): ByteArray? {
        val bytes = read(file) ?: return null
        return pdfSlice(bytes)
    }

    private fun read(file: File): ByteArray? {
        if (!file.isFile || !file.canRead()) return null
        return try {
            file.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeText(records: List<ByteArray>, header: MobiHeader): String? {
        val available = (records.size - 1).coerceAtLeast(0)
        val count = if (header.textRecords > 0) minOf(header.textRecords, available) else available
        if (count <= 0) return null
        val body = java.io.ByteArrayOutputStream()
        for (index in 1..count) {
            val raw = stripTrailing(records[index], header.extraFlags)
            val piece = when (header.compression) {
                COMPRESSION_NONE -> raw
                COMPRESSION_PALMDOC -> PalmDoc.decompress(raw, MAX_RECORD_OUTPUT)
                else -> return null
            }
            body.write(piece)
        }
        val decoded = body.toByteArray()
        val limited = if (header.textLength in 1 until decoded.size) decoded.copyOf(header.textLength) else decoded
        return try {
            String(limited, header.charset).trimStart('\uFEFF')
        } catch (_: Exception) {
            null
        }
    }

    private fun coverImage(records: List<ByteArray>, header: MobiHeader): EmbeddedCover? {
        val index = header.coverRecord ?: return null
        val bytes = records.getOrNull(index) ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return null
        val media = imageMedia(bytes) ?: return null
        return EmbeddedCover(media, bytes)
    }

    private fun imageMedia(bytes: ByteArray): String? {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        if (bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()) {
            return "image/gif"
        }
        return null
    }

    internal fun pdfSlice(bytes: ByteArray): ByteArray? {
        val start = indexOf(bytes, PDF_MAGIC, 0) 
        if (start < 0) return null
        val endMarker = indexOf(bytes, EOF_MAGIC, start)
        val end = if (endMarker < 0) bytes.size else minOf(bytes.size, endMarker + EOF_MAGIC.size)
        if (end <= start) return null
        return bytes.copyOfRange(start, end)
    }

    private fun indexOf(data: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || data.size < needle.size) return -1
        val last = data.size - needle.size
        var index = from.coerceAtLeast(0)
        while (index <= last) {
            var matched = true
            for (offset in needle.indices) {
                if (data[index + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return index
            index++
        }
        return -1
    }

    private fun palmRecords(bytes: ByteArray): List<ByteArray>? {
        if (bytes.size < 78) return null
        val count = u16(bytes, 76)
        if (count <= 0 || count > 65_535) return null
        val table = 78
        if (bytes.size < table + count * 8) return null
        val offsets = LongArray(count)
        for (index in 0 until count) {
            val offset = u32(bytes, table + index * 8)
            if (offset < 0 || offset > bytes.size) return null
            offsets[index] = offset
        }
        val records = ArrayList<ByteArray>(count)
        for (index in 0 until count) {
            val start = offsets[index].toInt()
            val end = if (index + 1 < count) offsets[index + 1].toInt() else bytes.size
            if (start > end || end > bytes.size) return null
            records += bytes.copyOfRange(start, end)
        }
        return records
    }

    private fun readHeader(record: ByteArray): MobiHeader? {
        if (record.size < 16) return null
        val compression = u16(record, 0)
        if (compression < 0) return null
        val textLength = u32(record, 4).toInt()
        val textRecords = u16(record, 8)
        val encryption = u16(record, 12)
        if (textRecords < 0 || encryption < 0) return null
        val mobi = record.size >= 20 &&
            record[16] == 'M'.code.toByte() &&
            record[17] == 'O'.code.toByte() &&
            record[18] == 'B'.code.toByte() &&
            record[19] == 'I'.code.toByte()
        if (!mobi) {
            return MobiHeader(
                compression = compression,
                textLength = textLength.coerceAtLeast(0),
                textRecords = textRecords,
                encryption = encryption,
                charset = Charsets.UTF_8,
                title = null,
                author = null,
                extraFlags = 0,
                firstImage = -1,
                coverRecord = null,
            )
        }
        val headerLength = u32(record, 0x14).toInt()
        val encoding = u32(record, 0x1C)
        val charset = if (encoding == 65001L) Charsets.UTF_8 else Charset.forName("windows-1252")
        val title = readName(record)
        val exth = if (headerLength > 0 && hasExth(record)) readExth(record, 16 + headerLength) else emptyMap()
        val author = exth[100]?.toString(charset)?.trim()?.ifBlank { null }
        val updatedTitle = exth[503]?.toString(charset)?.trim()?.ifBlank { null }
        val firstImage = if (record.size >= 0x70) u32(record, 0x6C).toInt() else -1
        val coverOffset = exth[201]?.let { value -> if (value.size >= 4) u32(value, 0).toInt() else -1 } ?: -1
        val coverRecord = if (firstImage > 0 && coverOffset >= 0) firstImage + coverOffset else null
        val extraFlags = if (headerLength >= 0xE4 && record.size >= 0xF4) u16(record, 0xF2) else 0
        return MobiHeader(
            compression = compression,
            textLength = textLength.coerceAtLeast(0),
            textRecords = textRecords,
            encryption = encryption,
            charset = charset,
            title = updatedTitle ?: title,
            author = author,
            extraFlags = extraFlags.coerceAtLeast(0),
            firstImage = firstImage,
            coverRecord = coverRecord,
        )
    }

    private fun hasExth(record: ByteArray): Boolean {
        if (record.size < 0x84) return false
        val flags = u32(record, 0x80)
        return flags and 0x40L != 0L
    }

    private fun readName(record: ByteArray): String? {
        if (record.size < 0x5C) return null
        val offset = u32(record, 0x54).toInt()
        val length = u32(record, 0x58).toInt()
        if (offset <= 0 || length <= 0 || offset > record.size || length > record.size - offset) return null
        return record.copyOfRange(offset, offset + length).toString(Charsets.UTF_8).trim().ifBlank { null }
    }

    private fun readExth(record: ByteArray, offset: Int): Map<Int, ByteArray> {
        if (offset < 0 || offset + 12 > record.size) return emptyMap()
        if (record[offset] != 'E'.code.toByte() ||
            record[offset + 1] != 'X'.code.toByte() ||
            record[offset + 2] != 'T'.code.toByte() ||
            record[offset + 3] != 'H'.code.toByte()
        ) {
            return emptyMap()
        }
        val count = u32(record, offset + 8).toInt()
        if (count <= 0 || count > 4096) return emptyMap()
        val values = linkedMapOf<Int, ByteArray>()
        var cursor = offset + 12
        repeat(count) {
            if (cursor + 8 > record.size) return values
            val type = u32(record, cursor).toInt()
            val length = u32(record, cursor + 4).toInt()
            if (length < 8 || cursor + length > record.size) return values
            val data = record.copyOfRange(cursor + 8, cursor + length)
            if (type !in values) values[type] = data
            cursor += length
        }
        return values
    }

    internal fun stripTrailing(data: ByteArray, extraFlags: Int): ByteArray {
        if (extraFlags == 0 || data.isEmpty()) return data
        var end = data.size
        var flags = extraFlags ushr 1
        while (flags != 0) {
            if (flags and 1 != 0) {
                val size = trailingEntrySize(data, end)
                if (size <= 0 || size > end) return data
                end -= size
            }
            flags = flags ushr 1
        }
        if (extraFlags and 1 != 0 && end > 0) {
            val overlap = (data[end - 1].toInt() and 0x03) + 1
            if (overlap in 1..end) end -= overlap
        }
        return if (end == data.size) data else data.copyOf(end.coerceAtLeast(0))
    }

    private fun trailingEntrySize(data: ByteArray, size: Int): Int {
        var value = 0
        var bit = 0
        var remaining = size
        while (true) {
            if (remaining <= 0) return 0
            val current = data[remaining - 1].toInt() and 0xFF
            remaining--
            value = value or ((current and 0x7F) shl bit)
            bit += 7
            if (current and 0x80 != 0 || bit >= 28) return value
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 1 >= bytes.size) return -1
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 3 >= bytes.size) return -1
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    private data class MobiHeader(
        val compression: Int,
        val textLength: Int,
        val textRecords: Int,
        val encryption: Int,
        val charset: Charset,
        val title: String?,
        val author: String?,
        val extraFlags: Int,
        val firstImage: Int,
        val coverRecord: Int?,
    )

    private val PDF_MAGIC = "%PDF-".toByteArray()
    private val EOF_MAGIC = "%%EOF".toByteArray()
}
