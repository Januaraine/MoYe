package app.moye.core.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import app.moye.core.model.BookFormat
import app.moye.core.model.WritingMode
import java.io.File

@Serializable
data class BookRecord(
    val id: String,
    val title: String,
    val author: String? = null,
    val format: BookFormat,
    val relativePath: String,
    val declaredWritingMode: WritingMode? = null,
    val charOffset: Long = 0,
    val totalChars: Long = 0,
    val readingDurationMs: Long = 0,
    val importedAtEpochMs: Long = 0,
    val coverRelativePath: String? = null,
    val contentHash: String? = null,
)

private sealed class ShelfEntry {
    data class Book(val record: BookRecord) : ShelfEntry()

    data class Opaque(val element: JsonElement) : ShelfEntry()
}

class FileLibraryStore(private val file: File) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): List<BookRecord> = synchronized(file) {
        readEntries().mapNotNull { (it as? ShelfEntry.Book)?.record }
    }

    fun save(books: List<BookRecord>) = synchronized(file) {
        val current = readEntries()
        val incoming = books.associateBy { it.id }
        val written = mutableSetOf<String>()
        val out = mutableListOf<JsonElement>()
        for (entry in current) {
            when (entry) {
                is ShelfEntry.Opaque -> out += entry.element
                is ShelfEntry.Book -> {
                    val updated = incoming[entry.record.id] ?: continue
                    out += json.encodeToJsonElement(BookRecord.serializer(), updated)
                    written += updated.id
                }
            }
        }
        for (book in books) {
            if (book.id !in written) {
                out += json.encodeToJsonElement(BookRecord.serializer(), book)
            }
        }
        write(buildJsonObject { put("books", JsonArray(out)) })
    }

    private fun readEntries(): List<ShelfEntry> {
        if (!file.exists()) return emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList()
        val root = json.parseToJsonElement(text) as? JsonObject
            ?: error("Shelf file is not a JSON object")
        val books = root["books"] as? JsonArray
            ?: error("Shelf file has no books array")
        return books.map { element ->
            val obj = element as? JsonObject
            if (obj == null) {
                ShelfEntry.Opaque(element)
            } else {
                try {
                    ShelfEntry.Book(json.decodeFromJsonElement(BookRecord.serializer(), obj))
                } catch (_: Exception) {
                    ShelfEntry.Opaque(element)
                }
            }
        }
    }

    private fun write(root: JsonObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(JsonObject.serializer(), root))
        if (file.exists() && !file.delete()) {
            file.writeText(tmp.readText())
            tmp.delete()
            return
        }
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}

class Library(private val store: FileLibraryStore) {
    fun list(): List<BookRecord> = store.load().sortedByDescending { it.importedAtEpochMs }

    fun get(id: String): BookRecord? = store.load().find { it.id == id }

    fun add(record: BookRecord) {
        val books = store.load().filterNot { it.id == record.id }.toMutableList()
        books += record
        store.save(books)
    }

    fun updateMetadata(id: String, title: String, author: String?): BookRecord? {
        return update(id) { current ->
            val nextTitle = title.trim().ifEmpty { current.title }
            val nextAuthor = author?.trim()?.ifEmpty { null }
            current.copy(title = nextTitle, author = nextAuthor)
        }
    }

    fun updateCover(id: String, coverRelativePath: String): BookRecord? {
        return update(id) { current -> current.copy(coverRelativePath = coverRelativePath) }
    }

    fun updateContentHash(id: String, contentHash: String): BookRecord? {
        return update(id) { current -> current.copy(contentHash = contentHash) }
    }

    fun findByContentHash(hash: String): BookRecord? {
        if (hash.isBlank()) return null
        return store.load().find { it.contentHash == hash }
    }

    fun updateProgress(id: String, charOffset: Long, totalChars: Long? = null): BookRecord? {
        return update(id) { current ->
            val total = (totalChars ?: current.totalChars).coerceAtLeast(0)
            current.copy(charOffset = charOffset.coerceIn(0, total), totalChars = total)
        }
    }

    fun addReadingTime(id: String, deltaMs: Long): BookRecord? {
        if (deltaMs <= 0L) return get(id)
        return update(id) { current ->
            current.copy(readingDurationMs = current.readingDurationMs + deltaMs)
        }
    }

    fun remove(id: String): BookRecord? {
        val books = store.load().toMutableList()
        val existing = books.find { it.id == id } ?: return null
        books.removeAll { it.id == id }
        store.save(books)
        return existing
    }

    private fun update(id: String, transform: (BookRecord) -> BookRecord): BookRecord? {
        val books = store.load().toMutableList()
        val index = books.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = transform(books[index])
        books[index] = updated
        store.save(books)
        return updated
    }
}

fun filterBooks(books: List<BookRecord>, query: String): List<BookRecord> {
    val needle = query.trim()
    if (needle.isEmpty()) return books
    return books.filter { book ->
        book.title.contains(needle, ignoreCase = true) ||
            book.author?.contains(needle, ignoreCase = true) == true
    }
}
