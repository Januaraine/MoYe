package app.moye.core.importing

import app.moye.core.model.BookFormat
import app.moye.core.model.Delivery
import app.moye.core.model.ImportError
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExtraFormatTest {
    @Test
    fun detectsTheAddedFormatsFromNameOrMime() {
        assertEquals(BookFormat.PDF, BookParser.detectFormat("notes.PDF", null))
        assertEquals(BookFormat.CBZ, BookParser.detectFormat("卷.cbz", "application/octet-stream"))
        assertEquals(BookFormat.CBR, BookParser.detectFormat(null, "application/vnd.comicbook-rar"))
        assertEquals(BookFormat.AZW, BookParser.detectFormat(null, "application/x-mobipocket-ebook"))
        assertEquals(BookFormat.AZW4, BookParser.detectFormat("replica.azw4", "application/octet-stream"))
        assertEquals(BookFormat.TXT, BookParser.detectFormat("notes.txt", "application/pdf"))
        assertEquals(null, BookParser.detectFormat("book.azw3", null))
    }

    @Test
    fun readsAzwTextMetadataAndChapters() {
        val dir = Files.createTempDirectory("moye-azw").toFile()
        val file = File(dir, "boat.azw")
        val html = "<html><body><p>第一章 山门</p><p>少年出了山。</p></body></html>".toByteArray()
        file.writeBytes(palmDatabase(listOf(mobiHeader(html, "Paper Boat", "Lin"), html)))
        val book = assertIs<ParseResult.Ok>(BookParser.parse(BookFormat.AZW, file, "boat.azw")).book
        assertEquals(Delivery.TEXT, book.delivery)
        assertEquals("Paper Boat", book.title)
        assertEquals("Lin", book.author)
        assertEquals("第一章 山门", book.chapters.single().title)
        assertTrue(book.text.contains("少年出了山。"))
    }

    @Test
    fun rejectsEncryptedAzwWithoutReadingIt() {
        val dir = Files.createTempDirectory("moye-azw-drm").toFile()
        val file = File(dir, "locked.azw")
        val html = "<p>secret</p>".toByteArray()
        file.writeBytes(palmDatabase(listOf(mobiHeader(html, "Locked", null, encryption = 2), html)))
        val error = assertIs<ParseResult.Err>(BookParser.parse(BookFormat.AZW, file, "locked.azw"))
        assertEquals(ImportError.PROTECTED, error.error)
    }

    @Test
    fun decompressesPalmDocBackReferences() {
        val compressed = byteArrayOf(0x61, 0x62, 0x63, 0x80.toByte(), 0x18)
        assertEquals("abcabc", PalmDoc.decompress(compressed).toString(Charset.forName("US-ASCII")))
    }

    @Test
    fun readsPdfAndAzw4AsPageBooks() {
        val dir = Files.createTempDirectory("moye-pdf").toFile()
        val pdf = File(dir, "essay.pdf")
        val payload = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n".toByteArray()
        pdf.writeBytes(payload)
        val parsed = assertIs<ParseResult.Ok>(BookParser.parse(BookFormat.PDF, pdf, "essay.pdf")).book
        assertEquals(Delivery.PAGES, parsed.delivery)
        assertEquals("essay", parsed.title)

        val broken = File(dir, "broken.pdf").apply { writeText("not a pdf") }
        assertEquals(ImportError.CORRUPT, (BookParser.parse(BookFormat.PDF, broken, "broken.pdf") as ParseResult.Err).error)

        val azw4 = File(dir, "replica.azw4")
        azw4.writeBytes(palmDatabase(listOf(mobiHeader(ByteArray(0), "Replica", "Lin"), payload)))
        val replica = assertIs<ParseResult.Ok>(BookParser.parse(BookFormat.AZW4, azw4, "replica.azw4")).book
        assertEquals(Delivery.PAGES, replica.delivery)
        assertEquals("Replica", replica.title)
        assertTrue(AzwParser.extractPdf(azw4)?.decodeToString()?.startsWith("%PDF-") == true)
    }

    @Test
    fun readsCbzPagesInNaturalOrderAndRejectsBadArchives() {
        val dir = Files.createTempDirectory("moye-cbz").toFile()
        val file = File(dir, "story.cbz")
        val second = byteArrayOf(2, 2, 2)
        val tenth = byteArrayOf(10, 10)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.put("page10.jpg", tenth)
            zip.put("notes.txt", "ignore".toByteArray())
            zip.put("page2.jpg", second)
        }
        val book = assertIs<ParseResult.Ok>(BookParser.parse(BookFormat.CBZ, file, "story.cbz")).book
        assertEquals(Delivery.PAGES, book.delivery)
        assertEquals(2, book.pageCount)
        assertEquals("story", book.title)
        assertTrue(book.cover?.bytes?.contentEquals(second) == true)
        assertTrue(ComicArchive.read(file, BookFormat.CBZ, 1)?.contentEquals(tenth) == true)

        val fake = File(dir, "bad.cbr").apply { writeText("not a rar") }
        assertEquals(ImportError.CORRUPT, (BookParser.parse(BookFormat.CBR, fake, "bad.cbr") as ParseResult.Err).error)
        val empty = File(dir, "empty.cbz")
        ZipOutputStream(empty.outputStream()).use { zip -> zip.put("readme.txt", "no pages".toByteArray()) }
        assertEquals(ImportError.EMPTY, (BookParser.parse(BookFormat.CBZ, empty, "empty.cbz") as ParseResult.Err).error)
    }

    private fun palmDatabase(records: List<ByteArray>): ByteArray {
        val header = ByteArray(78 + records.size * 8)
        "BOOK".toByteArray().copyInto(header, 0x3C)
        "MOBI".toByteArray().copyInto(header, 0x40)
        header[76] = (records.size shr 8).toByte()
        header[77] = records.size.toByte()
        val body = ByteArrayOutputStream()
        var offset = header.size
        records.forEachIndexed { index, record ->
            writeInt(header, 78 + index * 8, offset)
            body.write(record)
            offset += record.size
        }
        return header + body.toByteArray()
    }

    private fun mobiHeader(text: ByteArray, title: String, author: String?, encryption: Int = 0): ByteArray {
        val titleBytes = title.toByteArray()
        val exth = if (author == null) ByteArray(0) else exthAuthor(author)
        val mobiLength = 0xE8
        val record = ByteArray(16 + mobiLength + exth.size + titleBytes.size)
        writeShort(record, 0, 1)
        writeInt(record, 4, text.size)
        writeShort(record, 8, 1)
        writeShort(record, 10, 4096)
        writeShort(record, 12, encryption)
        "MOBI".toByteArray().copyInto(record, 16)
        writeInt(record, 0x14, mobiLength)
        writeInt(record, 0x18, 2)
        writeInt(record, 0x1C, 65001)
        if (author != null) writeInt(record, 0x80, 0x40)
        if (exth.isNotEmpty()) exth.copyInto(record, 16 + mobiLength)
        val titleOffset = 16 + mobiLength + exth.size
        titleBytes.copyInto(record, titleOffset)
        writeInt(record, 0x54, titleOffset)
        writeInt(record, 0x58, titleBytes.size)
        return record
    }

    private fun exthAuthor(author: String): ByteArray {
        val data = author.toByteArray()
        val recordLength = 8 + data.size
        val out = ByteArray(12 + recordLength)
        "EXTH".toByteArray().copyInto(out)
        writeInt(out, 4, out.size)
        writeInt(out, 8, 1)
        writeInt(out, 12, 100)
        writeInt(out, 16, recordLength)
        data.copyInto(out, 20)
        return out
    }

    private fun writeShort(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value shr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value shr 24).toByte()
        target[offset + 1] = (value shr 16).toByte()
        target[offset + 2] = (value shr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun ZipOutputStream.put(path: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(path))
        write(bytes)
        closeEntry()
    }
}
