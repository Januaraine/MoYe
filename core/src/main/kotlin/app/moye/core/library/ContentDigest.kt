package app.moye.core.library

import java.io.File
import java.security.MessageDigest

object ContentDigest {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

/**
 * Duplicate imports are the same file bytes, not the same title.
 * [claim] records a new hash and returns false when that hash was already seen.
 */
object ImportIdentity {
    fun claim(knownHashes: MutableSet<String>, hash: String): Boolean = knownHashes.add(hash)
}
