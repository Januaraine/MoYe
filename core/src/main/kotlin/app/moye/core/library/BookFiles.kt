package app.moye.core.library

import java.io.File
import java.io.InputStream

class BookFiles(private val root: File) {
    fun place(id: String, extension: String, source: File): String {
        val dir = File(root, id)
        dir.mkdirs()
        val safeExt = extension.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "bin" }
        val target = File(dir, "book.$safeExt")
        source.copyTo(target, overwrite = true)
        return "$id/book.$safeExt"
    }

    fun resolve(relativePath: String): File {
        val rootCanon = root.canonicalFile
        val fileCanon = File(rootCanon, relativePath).canonicalFile
        val rootPath = rootCanon.path + File.separator
        if (fileCanon.path != rootCanon.path && !fileCanon.path.startsWith(rootPath)) {
            throw IllegalArgumentException("Invalid book path")
        }
        return fileCanon
    }

    fun deleteCopy(relativePath: String): Boolean {
        val file = try {
            resolve(relativePath)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val deleted = !file.exists() || file.delete()
        val dir = file.parentFile
        val rootCanon = root.canonicalFile
        if (dir != null && dir.canonicalFile.path.startsWith(rootCanon.path + File.separator)) {
            dir.deleteRecursively()
        }
        return deleted || !file.exists()
    }
}

fun copyWithLimit(input: InputStream, target: File, limitBytes: Long): Boolean {
    target.parentFile?.mkdirs()
    target.outputStream().use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limitBytes) {
                output.close()
                target.delete()
                return false
            }
            output.write(buffer, 0, read)
        }
    }
    return true
}

private const val DEFAULT_BUFFER = 8192
