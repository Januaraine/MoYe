package app.moye.core.importing

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object TxtDecoder {
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return normalize(bytes.decodeToString(3))
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return normalize(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE))
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return normalize(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE))
        }
        val utf8 = try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
        val text = utf8 ?: String(bytes, Charset.forName("GB18030"))
        return normalize(text)
    }

    fun normalize(text: String): String {
        return text.replace("\r\n", "\n").replace('\r', '\n')
    }
}
