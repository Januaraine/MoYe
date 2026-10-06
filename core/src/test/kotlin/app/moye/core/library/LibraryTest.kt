package app.moye.core.library

import app.moye.core.model.BookFormat
import app.moye.core.model.RemovalChoice
import app.moye.core.model.RemovalResult
import app.moye.core.model.WritingMode
import app.moye.core.model.effectiveWritingMode
import app.moye.core.settings.FileSettingsStore
import app.moye.core.settings.ReaderSettings
import app.moye.core.model.PageTurnDirection
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryTest {
    @Test
    fun recordsStayIndependentAcrossRestart() {
        val dir = Files.createTempDirectory("moye-lib").toFile()
        val store = FileLibraryStore(File(dir, "library.json"))
        val library = Library(store)
        library.add(sample("a", "Alpha", offset = 12, duration = 1000, total = 100))
        library.add(sample("b", "Beta", offset = 3, duration = 50, total = 80))
        library.updateProgress("a", 40)
        library.addReadingTime("b", 25)

        val reloaded = Library(FileLibraryStore(File(dir, "library.json")))
        assertEquals(40, reloaded.get("a")?.charOffset)
        assertEquals(3, reloaded.get("b")?.charOffset)
        assertEquals(1000, reloaded.get("a")?.readingDurationMs)
        assertEquals(75, reloaded.get("b")?.readingDurationMs)
        assertEquals("Renamed", reloaded.updateMetadata("a", "Renamed", "Writer")?.title)
        assertEquals("Writer", reloaded.get("a")?.author)
    }

    @Test
    fun metadataEditDoesNotRequireChangingTheBookFile() {
        val dir = Files.createTempDirectory("moye-meta").toFile()
        val library = Library(FileLibraryStore(File(dir, "library.json")))
        val book = File(dir, "books/a/book.txt")
        book.parentFile.mkdirs()
        book.writeText("body")
        library.add(sample("a", "Alpha").copy(relativePath = "a/book.txt"))
        library.updateMetadata("a", "New title", "")
        assertEquals("body", book.readText())
        assertEquals("New title", library.get("a")?.title)
        assertNull(library.get("a")?.author)
    }

    @Test
    fun removalKeepsOrDeletesOnlyTheSelectedCopy() {
        val dir = Files.createTempDirectory("moye-remove").toFile()
        val files = BookFiles(File(dir, "books"))
        val library = Library(FileLibraryStore(File(dir, "library.json")))
        val first = File(dir, "src-a.txt").apply { writeText("one") }
        val second = File(dir, "src-b.txt").apply { writeText("two") }
        library.add(sample("a", "Alpha").copy(relativePath = files.place("a", "txt", first)))
        library.add(sample("b", "Beta").copy(relativePath = files.place("b", "txt", second)))
        val removal = BookRemoval(library) { path -> files.deleteCopy(path) }

        assertEquals(RemovalResult.Cancelled, removal.remove("a", RemovalChoice.CANCEL))
        assertEquals("Alpha", library.get("a")?.title)
        assertTrue(files.resolve(library.get("a")!!.relativePath).exists())

        assertEquals(RemovalResult.RemovedKeepCopy, removal.remove("a", RemovalChoice.KEEP_COPY))
        assertNull(library.get("a"))
        assertTrue(File(dir, "books/a/book.txt").exists())
        assertEquals("Beta", library.get("b")?.title)

        assertEquals(RemovalResult.RemovedDeletedCopy, removal.remove("b", RemovalChoice.DELETE_COPY))
        assertNull(library.get("b"))
        assertFalse(File(dir, "books/b/book.txt").exists())
        assertTrue(File(dir, "books/a/book.txt").exists())
    }

    @Test
    fun failedDeleteDoesNotDropTheRecord() {
        val dir = Files.createTempDirectory("moye-fail").toFile()
        val library = Library(FileLibraryStore(File(dir, "library.json")))
        library.add(sample("a", "Alpha"))
        val removal = BookRemoval(library) { false }
        assertEquals(RemovalResult.Failed, removal.remove("a", RemovalChoice.DELETE_COPY))
        assertEquals("Alpha", library.get("a")?.title)
    }

    @Test
    fun searchMatchesTitleOrAuthorAndClears() {
        val books = listOf(
            sample("a", "墨页", author = "林"),
            sample("b", "Other", author = "Ada"),
        )
        assertEquals(listOf("a"), filterBooks(books, "墨").map { it.id })
        assertEquals(listOf("b"), filterBooks(books, "ada").map { it.id })
        assertEquals(listOf("a", "b"), filterBooks(books, "  ").map { it.id })
    }

    @Test
    fun settingsRoundTripAndStayInRange() {
        val file = File(Files.createTempDirectory("moye-settings").toFile(), "settings.json")
        val store = FileSettingsStore(file)
        store.save(
            ReaderSettings(
                fontSizeSp = 99f,
                pageTurnDirection = PageTurnDirection.VERTICAL,
                txtWritingMode = WritingMode.VERTICAL,
                playbackSpeed = 1.5f,
                typewriterEnabled = false,
                typewriterSpeed = 2.5f,
                languageTag = "en",
            ),
        )
        val loaded = FileSettingsStore(file).load()
        assertEquals(36f, loaded.fontSizeSp)
        assertEquals(PageTurnDirection.VERTICAL, loaded.pageTurnDirection)
        assertEquals(WritingMode.VERTICAL, loaded.txtWritingMode)
        assertEquals(1.5f, loaded.playbackSpeed)
        assertFalse(loaded.typewriterEnabled)
        assertEquals(2.5f, loaded.typewriterSpeed)
        assertEquals("en", loaded.languageTag)
        assertEquals(WritingMode.VERTICAL, effectiveWritingMode(null, loaded.txtWritingMode))
        assertEquals(WritingMode.HORIZONTAL, effectiveWritingMode(WritingMode.HORIZONTAL, WritingMode.VERTICAL))
    }

    @Test
    fun olderSettingsWithoutTypewriterFieldsStayReadable() {
        val file = File(Files.createTempDirectory("moye-old-settings").toFile(), "settings.json")
        file.writeText(
            """{"fontSizeSp":18.0,"readingMode":"SENTENCE","playbackSpeed":1.0,"languageTag":"zh"}""",
        )
        val loaded = FileSettingsStore(file).load()
        assertTrue(loaded.typewriterEnabled)
        assertEquals(1f, loaded.typewriterSpeed)
        assertEquals("zh", loaded.languageTag)
    }

    @Test
    fun legacyTypewriterPresetsBecomeSliderSpeeds() {
        val slowFile = File(Files.createTempDirectory("moye-slow").toFile(), "settings.json")
        slowFile.writeText("""{"typewriterSpeed":"SLOW"}""")
        assertEquals(0.5f, FileSettingsStore(slowFile).load().typewriterSpeed)
        val fastFile = File(Files.createTempDirectory("moye-fast").toFile(), "settings.json")
        fastFile.writeText("""{"typewriterSpeed":"FAST","playbackSpeed":9.0}""")
        val fast = FileSettingsStore(fastFile).load()
        assertEquals(2.5f, fast.typewriterSpeed)
        assertEquals(3f, fast.playbackSpeed)
    }

    @Test
    fun duplicateImportUsesContentHashNotTitle() {
        val dir = Files.createTempDirectory("moye-hash").toFile()
        val library = Library(FileLibraryStore(File(dir, "library.json")))
        val first = File(dir, "one.txt").apply { writeText("same bytes") }
        val renamed = File(dir, "other-title.txt").apply { writeText("same bytes") }
        val different = File(dir, "different.txt").apply { writeText("different bytes") }
        val hash = ContentDigest.sha256(first)
        assertEquals(hash, ContentDigest.sha256(renamed))
        assertTrue(hash != ContentDigest.sha256(different))
        library.add(sample("a", "Same Title").copy(contentHash = hash))
        assertEquals("a", library.findByContentHash(hash)?.id)
        assertEquals("Same Title", library.findByContentHash(ContentDigest.sha256(renamed))?.title)
        assertNull(library.findByContentHash(ContentDigest.sha256(different)))

        val seen = mutableSetOf(hash)
        assertFalse(ImportIdentity.claim(seen, hash))
        assertTrue(ImportIdentity.claim(seen, ContentDigest.sha256(different)))
        val reloaded = Library(FileLibraryStore(File(dir, "library.json")))
        assertEquals(hash, reloaded.get("a")?.contentHash)
    }

    @Test
    fun coverFileIsRemovedOnlyWithThatBooksCopy() {
        val dir = Files.createTempDirectory("moye-cover-file").toFile()
        val files = BookFiles(File(dir, "books"))
        val library = Library(FileLibraryStore(File(dir, "library.json")))
        val first = File(dir, "src-a.txt").apply { writeText("one") }
        val second = File(dir, "src-b.txt").apply { writeText("two") }
        val firstPath = files.place("a", "txt", first)
        val secondPath = files.place("b", "txt", second)
        val firstCover = files.placeCover("a", "jpg", byteArrayOf(1, 2, 3))
        files.placeCover("b", "png", byteArrayOf(4, 5))
        library.add(sample("a", "Alpha").copy(relativePath = firstPath, coverRelativePath = firstCover))
        library.add(sample("b", "Beta").copy(relativePath = secondPath, coverRelativePath = "b/cover.png"))
        assertEquals("b/cover.png", library.updateCover("b", "b/cover.png")?.coverRelativePath)
        val removal = BookRemoval(library) { path -> files.deleteCopy(path) }
        assertEquals(RemovalResult.RemovedDeletedCopy, removal.remove("a", RemovalChoice.DELETE_COPY))
        assertFalse(File(dir, "books/a/cover.jpg").exists())
        assertTrue(File(dir, "books/b/cover.png").exists())
        assertTrue(File(dir, "books/b/book.txt").exists())
    }

    @Test
    fun rejectsPathsOutsideTheBookDirectory() {
        val root = Files.createTempDirectory("moye-files").toFile()
        val files = BookFiles(root)
        val source = File(root, "in.txt").apply { writeText("x") }
        val relative = files.place("id", "txt", source)
        assertTrue(files.resolve(relative).readText() == "x")
        assertTrue(runCatching { files.resolve("../secret.txt") }.isFailure)
    }

    private fun sample(
        id: String,
        title: String,
        author: String? = null,
        offset: Long = 0,
        duration: Long = 0,
        total: Long = 10,
    ) = BookRecord(
        id = id,
        title = title,
        author = author,
        format = BookFormat.TXT,
        relativePath = "$id/book.txt",
        charOffset = offset,
        totalChars = total,
        readingDurationMs = duration,
        importedAtEpochMs = if (id == "a") 2 else 1,
    )
}
