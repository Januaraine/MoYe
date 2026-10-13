package app.moye.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.moye.core.importing.BookParser
import app.moye.core.importing.EpubParser
import app.moye.core.importing.ParseResult
import app.moye.core.library.BookFiles
import app.moye.core.library.BookRecord
import app.moye.core.library.BookRemoval
import app.moye.core.library.ContentDigest
import app.moye.core.library.ImportIdentity
import app.moye.core.library.Library
import app.moye.core.library.copyWithLimit
import app.moye.core.model.BookFormat
import app.moye.core.model.ContentError
import app.moye.core.model.EmbeddedCover
import app.moye.core.model.ImportError
import app.moye.core.model.ParsedBook
import app.moye.core.model.RemovalChoice
import app.moye.core.model.RemovalResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ImportReport(
    val imported: Int = 0,
    val duplicates: Int = 0,
    val failures: Int = 0,
    val firstError: ImportError? = null,
)

private sealed class ImportOne {
    data object Added : ImportOne()

    data object Duplicate : ImportOne()

    data class Failed(val error: ImportError) : ImportOne()
}

sealed class ShelfSnapshot {
    data class Ready(val books: List<BookRecord>) : ShelfSnapshot()

    data object Failed : ShelfSnapshot()
}

sealed class ContentLoad {
    data class Ready(val record: BookRecord, val book: ParsedBook) : ContentLoad()

    data class Failed(val error: ContentError) : ContentLoad()
}

class BookRepository(
    private val context: Context,
    private val library: Library,
    private val bookFiles: BookFiles,
) {
    private val removal = BookRemoval(library) { relativePath -> bookFiles.deleteCopy(relativePath) }

    fun loadShelf(): ShelfSnapshot {
        return try {
            ShelfSnapshot.Ready(library.list())
        } catch (_: Exception) {
            ShelfSnapshot.Failed
        }
    }

    suspend fun importAll(uris: List<Uri>): ImportReport = withContext(Dispatchers.IO) {
        var imported = 0
        var duplicates = 0
        var failures = 0
        var firstError: ImportError? = null
        val known = knownContentHashes()
        for (uri in uris) {
            when (val outcome = importOne(uri, known)) {
                ImportOne.Added -> imported++
                ImportOne.Duplicate -> duplicates++
                is ImportOne.Failed -> {
                    failures++
                    if (firstError == null) firstError = outcome.error
                }
            }
        }
        ImportReport(imported, duplicates, failures, firstError)
    }

    private fun knownContentHashes(): MutableSet<String> {
        val known = mutableSetOf<String>()
        for (book in library.list()) {
            val hash = book.contentHash ?: hashStoredCopy(book)
            if (!hash.isNullOrBlank()) known += hash
        }
        return known
    }

    private fun hashStoredCopy(book: BookRecord): String? {
        val file = try {
            bookFiles.resolve(book.relativePath)
        } catch (_: Exception) {
            return null
        }
        if (!file.isFile) return null
        val hash = try {
            ContentDigest.sha256(file)
        } catch (_: Exception) {
            return null
        }
        library.updateContentHash(book.id, hash)
        return hash
    }

    private fun importOne(uri: Uri, known: MutableSet<String>): ImportOne {
        val name = displayName(uri)
        val mime = context.contentResolver.getType(uri)
        val format = BookParser.detectFormat(name, mime) ?: return ImportOne.Failed(ImportError.UNSUPPORTED_FORMAT)
        val temp = File.createTempFile("moye-import", ".part", context.cacheDir)
        var claimed = false
        var hash = ""
        try {
            val stream = context.contentResolver.openInputStream(uri) ?: return ImportOne.Failed(ImportError.UNREADABLE)
            val copied = stream.use { copyWithLimit(it, temp, BookParser.MAX_BYTES) }
            if (!copied) return ImportOne.Failed(ImportError.TOO_LARGE)
            hash = ContentDigest.sha256(temp)
            if (!ImportIdentity.claim(known, hash)) return ImportOne.Duplicate
            claimed = true
            return when (val parsed = BookParser.parse(format, temp, name)) {
                is ParseResult.Err -> ImportOne.Failed(parsed.error)
                is ParseResult.Ok -> {
                    val id = UUID.randomUUID().toString()
                    val extension = if (format == BookFormat.EPUB) "epub" else "txt"
                    val relativePath = bookFiles.place(id, extension, temp)
                    val fallback = name?.substringAfterLast('/')?.substringBeforeLast('.')?.ifBlank { null } ?: "Untitled"
                    library.add(
                        BookRecord(
                            id = id,
                            title = parsed.book.title?.ifBlank { null } ?: fallback,
                            author = parsed.book.author?.ifBlank { null },
                            format = format,
                            relativePath = relativePath,
                            declaredWritingMode = parsed.book.declaredWritingMode,
                            charOffset = 0,
                            totalChars = parsed.book.text.length.toLong(),
                            importedAtEpochMs = System.currentTimeMillis(),
                            coverRelativePath = parsed.book.cover?.let { storeCover(id, it) },
                            contentHash = hash,
                        ),
                    )
                    claimed = false
                    ImportOne.Added
                }
            }
        } catch (_: Exception) {
            return ImportOne.Failed(ImportError.UNREADABLE)
        } finally {
            if (claimed) known.remove(hash)
            temp.delete()
        }
    }

    fun loadContent(id: String): ContentLoad {
        val record = try {
            library.get(id)
        } catch (_: Exception) {
            return ContentLoad.Failed(ContentError.MISSING)
        } ?: return ContentLoad.Failed(ContentError.MISSING)
        val file = try {
            bookFiles.resolve(record.relativePath)
        } catch (_: Exception) {
            return ContentLoad.Failed(ContentError.MISSING)
        }
        if (!file.isFile) return ContentLoad.Failed(ContentError.MISSING)
        return when (val parsed = BookParser.parse(record.format, file, record.title)) {
            is ParseResult.Err -> ContentLoad.Failed(
                if (parsed.error == ImportError.EMPTY) ContentError.EMPTY else ContentError.CORRUPT,
            )
            is ParseResult.Ok -> {
                library.updateProgress(id, record.charOffset, parsed.book.text.length.toLong())
                val refreshed = library.get(id) ?: record
                ContentLoad.Ready(ensureCover(refreshed, parsed.book), parsed.book)
            }
        }
    }

    fun find(id: String): BookRecord? = library.get(id)

    fun coverFile(record: BookRecord): File? {
        val relative = record.coverRelativePath ?: return null
        return try {
            bookFiles.resolve(relative).takeIf { it.isFile }
        } catch (_: Exception) {
            null
        }
    }

    fun updateMetadata(id: String, title: String, author: String?) = library.updateMetadata(id, title, author)

    fun updateProgress(id: String, charOffset: Long, totalChars: Long) {
        library.updateProgress(id, charOffset, totalChars)
    }

    fun addReadingTime(id: String, deltaMs: Long) {
        library.addReadingTime(id, deltaMs)
    }

    fun remove(id: String, choice: RemovalChoice): RemovalResult = removal.remove(id, choice)

    fun syncCovers(): Boolean {
        var changed = false
        for (record in library.list()) {
            if (record.format != BookFormat.EPUB) continue
            val source = try {
                bookFiles.resolve(record.relativePath)
            } catch (_: Exception) {
                continue
            }
            if (!source.isFile) continue
            val cover = try {
                EpubParser.readCover(source)
            } catch (_: Exception) {
                null
            } ?: continue
            if (sameCover(record, cover)) continue
            val path = storeCover(record.id, cover) ?: continue
            if (library.updateCover(record.id, path) != null) changed = true
        }
        return changed
    }

    private fun storeCover(id: String, cover: EmbeddedCover): String? {
        return try {
            bookFiles.placeCover(id, cover.extension, cover.bytes)
        } catch (_: Exception) {
            null
        }
    }

    private fun ensureCover(record: BookRecord, book: ParsedBook): BookRecord {
        val cover = book.cover ?: return record
        if (sameCover(record, cover)) return record
        val path = storeCover(record.id, cover) ?: return record
        return library.updateCover(record.id, path) ?: record.copy(coverRelativePath = path)
    }

    private fun sameCover(record: BookRecord, cover: EmbeddedCover): Boolean {
        val existing = coverFile(record) ?: return false
        return existing.length() == cover.bytes.size.toLong() && existing.readBytes().contentEquals(cover.bytes)
    }

    private fun displayName(uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment
    }
}
