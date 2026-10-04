package app.moye.core.importing

import app.moye.core.model.BookFormat
import app.moye.core.model.ImportError
import app.moye.core.model.WritingMode
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookParserTest {
    @Test
    fun readsUtf8AndGb18030Text() {
        val dir = Files.createTempDirectory("moye-txt").toFile()
        val utf = File(dir, "story.txt")
        utf.writeText("第一章 山门\n少年出了山。风很大。")
        val parsed = BookParser.parse(BookFormat.TXT, utf, "story.txt")
        val ok = assertIs<ParseResult.Ok>(parsed)
        assertEquals("story", ok.book.title)
        assertEquals("第一章 山门", ok.book.chapters.single().title)
        assertEquals(null, ok.book.declaredWritingMode)
        assertNull(ok.book.cover)
        assertTrue(ok.book.text.contains("风很大。"))

        val gbk = File(dir, "gbk.txt")
        gbk.writeBytes("你好，世界。".toByteArray(Charset.forName("GB18030")))
        val gbkParsed = assertIs<ParseResult.Ok>(BookParser.parse(BookFormat.TXT, gbk, "gbk.txt"))
        assertTrue(gbkParsed.book.text.contains("你好"))
    }

    @Test
    fun rejectsEmptyCorruptAndUnsupportedFiles() {
        val dir = Files.createTempDirectory("moye-bad").toFile()
        val empty = File(dir, "empty.txt").apply { writeText("   \n") }
        assertEquals(ImportError.EMPTY, (BookParser.parse(BookFormat.TXT, empty, "empty.txt") as ParseResult.Err).error)
        val binary = File(dir, "bad.txt").apply { writeBytes(byteArrayOf(0x41, 0, 0x42)) }
        assertEquals(ImportError.CORRUPT, (BookParser.parse(BookFormat.TXT, binary, "bad.txt") as ParseResult.Err).error)
        val fake = File(dir, "bad.epub").apply { writeText("not a zip") }
        assertEquals(ImportError.CORRUPT, (BookParser.parse(BookFormat.EPUB, fake, "bad.epub") as ParseResult.Err).error)
        assertEquals(null, BookParser.detectFormat("notes.pdf", "application/pdf"))
        assertEquals(BookFormat.EPUB, BookParser.detectFormat("book.EPUB", null))
        assertEquals(BookFormat.TXT, BookParser.detectFormat(null, "text/plain"))
    }

    @Test
    fun readsEpubMetadataChaptersAndWritingMode() {
        val dir = Files.createTempDirectory("moye-epub").toFile()
        val horizontal = File(dir, "h.epub")
        writeEpub(horizontal, vertical = false)
        val horizontalBook = assertIs<ParseResult.Ok>(BookParser.parse(BookFormat.EPUB, horizontal, "h.epub")).book
        assertEquals("Paper Boat", horizontalBook.title)
        assertEquals("Lin", horizontalBook.author)
        assertEquals(WritingMode.HORIZONTAL, horizontalBook.declaredWritingMode)
        assertNull(horizontalBook.cover)
        assertEquals(listOf("启程", "归来"), horizontalBook.chapters.map { it.title })
        assertTrue(horizontalBook.text.contains("山风很急。"))
        assertTrue(horizontalBook.text.contains("灯还亮着。"))
        assertTrue(horizontalBook.text.indexOf("山风很急。") >= horizontalBook.chapters[0].startOffset)
        assertTrue(horizontalBook.text.indexOf("灯还亮着。") >= horizontalBook.chapters[1].startOffset)

        val vertical = File(dir, "v.epub")
        writeEpub(vertical, vertical = true)
        val verticalBook = assertIs<ParseResult.Ok>(BookParser.parse(BookFormat.EPUB, vertical, "v.epub")).book
        assertEquals(WritingMode.VERTICAL, verticalBook.declaredWritingMode)
    }

    @Test
    fun extractsMarkedEpubCoverAndIgnoresOtherImages() {
        val dir = Files.createTempDirectory("moye-cover").toFile()
        val file = File(dir, "covered.epub")
        val coverBytes = byteArrayOf(0x1, 0x2, 0x3, 0x4)
        writeEpub(file, vertical = false, cover = coverBytes, decoy = byteArrayOf(0x9, 0x9))
        val book = assertIs<ParseResult.Ok>(BookParser.parse(BookFormat.EPUB, file, "covered.epub")).book
        assertTrue(book.cover?.bytes?.contentEquals(coverBytes) == true)
        assertEquals("jpg", book.cover?.extension)

        val metaOnly = File(dir, "meta.epub")
        writeEpub(metaOnly, vertical = false, cover = coverBytes, useCoverProperty = false)
        val metaBook = assertIs<ParseResult.Ok>(BookParser.parse(BookFormat.EPUB, metaOnly, "meta.epub")).book
        assertTrue(metaBook.cover?.bytes?.contentEquals(coverBytes) == true)
    }

    private fun writeEpub(
        file: File,
        vertical: Boolean,
        cover: ByteArray? = null,
        decoy: ByteArray? = null,
        useCoverProperty: Boolean = true,
    ) {
        val css = if (vertical) "body { writing-mode: vertical-rl; }" else "body { writing-mode: horizontal-tb; }"
        val chapter1 = """
            <html><head><title>启程</title></head><body>
            <h1 id="start">启程</h1><p>山风很急。</p>
            </body></html>
        """.trimIndent()
        val chapter2 = """
            <html><body><h1>归来</h1><p>灯还亮着。</p></body></html>
        """.trimIndent()
        val nav = """
            <html><body><nav epub:type="toc"><ol>
            <li><a href="c1.xhtml#start">启程</a></li>
            <li><a href="c2.xhtml">归来</a></li>
            </ol></nav></body></html>
        """.trimIndent()
        val coverMeta = if (cover == null) "" else """<meta name="cover" content="cover-img"/>"""
        val coverItem = if (cover == null) {
            ""
        } else {
            val properties = if (useCoverProperty) " properties=\"cover-image\"" else ""
            """<item id="cover-img" href="cover.jpg" media-type="image/jpeg"$properties/>"""
        }
        val decoyItem = if (decoy == null) "" else """<item id="art" href="art.png" media-type="image/png"/>"""
        val opf = """
            <package xmlns="http://www.idpf.org/2007/opf">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Paper Boat</dc:title>
                <dc:creator>Lin</dc:creator>
                $coverMeta
              </metadata>
              <manifest>
                <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="css" href="style.css" media-type="text/css"/>
                $coverItem
                $decoyItem
              </manifest>
              <spine>
                <itemref idref="c1"/>
                <itemref idref="c2"/>
              </spine>
            </package>
        """.trimIndent()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.put("mimetype", "application/epub+zip")
            zip.put("META-INF/container.xml", """
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent())
            zip.put("OEBPS/content.opf", opf)
            zip.put("OEBPS/style.css", css)
            zip.put("OEBPS/c1.xhtml", chapter1)
            zip.put("OEBPS/c2.xhtml", chapter2)
            zip.put("OEBPS/nav.xhtml", nav)
            if (cover != null) zip.putBytes("OEBPS/cover.jpg", cover)
            if (decoy != null) zip.putBytes("OEBPS/art.png", decoy)
        }
    }

    private fun ZipOutputStream.put(path: String, text: String) {
        putBytes(path, text.toByteArray())
    }

    private fun ZipOutputStream.putBytes(path: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(path))
        write(bytes)
        closeEntry()
    }
}
