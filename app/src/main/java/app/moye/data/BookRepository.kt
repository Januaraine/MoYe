package app.moye.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.moye.core.importing.BookParser
import app.moye.core.importing.ParseResult
import app.moye.core.library.BookFiles
import app.moye.core.library.BookRecord
import app.moye.core.library.BookRemoval
import app.moye.core.library.Library
import app.moye.core.library.copyWithLimit
import app.moye.core.model.BookFormat
import app.moye.core.model.ContentError
import app.moye.core.model.ImportError
import app.moye.core.model.ParsedBook
import app.moye.core.model.RemovalChoice
import app.moye.core.model.RemovalResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

sealed class ImportOutcome {
    data class Imported(val bookId: String) : ImportOutcome()

    data class Failed(val error: ImportError) : ImportOutcome()
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

    suspend fun import(uri: Uri): ImportOutcome = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val mime = context.contentResolver.getType(uri)
        val format = BookParser.detectFormat(name, mime)
            ?: return@withContext ImportOutcome.Failed(ImportError.UNSUPPORTED_FORMAT)
        val temp = File.createTempFile("moye-import", ".part", context.cacheDir)
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext ImportOutcome.Failed(ImportError.UNREADABLE)
            val copied = stream.use { copyWithLimit(it, temp, BookParser.MAX_BYTES) }
            if (!copied) return@withContext ImportOutcome.Failed(ImportError.TOO_LARGE)
            when (val parsed = BookParser.parse(format, temp, name)) {
                is ParseResult.Err -> ImportOutcome.Failed(parsed.error)
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
                        ),
                    )
                    ImportOutcome.Imported(id)
                }
            }
        } catch (_: Exception) {
            ImportOutcome.Failed(ImportError.UNREADABLE)
        } finally {
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
                ContentLoad.Ready(refreshed, parsed.book)
            }
        }
    }

    fun find(id: String): BookRecord? = library.get(id)

    fun updateMetadata(id: String, title: String, author: String?) = library.updateMetadata(id, title, author)

    fun updateProgress(id: String, charOffset: Long, totalChars: Long) {
        library.updateProgress(id, charOffset, totalChars)
    }

    fun addReadingTime(id: String, deltaMs: Long) {
        library.addReadingTime(id, deltaMs)
    }

    fun remove(id: String, choice: RemovalChoice): RemovalResult = removal.remove(id, choice)

    private fun displayName(uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment
    }
}
