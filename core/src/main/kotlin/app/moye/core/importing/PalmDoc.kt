package app.moye.core.importing

import java.io.ByteArrayOutputStream

internal object PalmDoc {
    fun decompress(data: ByteArray, limit: Int = 16 * 1024): ByteArray {
        val out = GrowingBytes(minOf(maxOf(data.size * 2, 64), limit))
        var index = 0
        while (index < data.size && out.size < limit) {
            val marker = data[index].toInt() and 0xFF
            index++
            when {
                marker in 0x01..0x08 -> {
                    val count = minOf(marker, data.size - index, limit - out.size)
                    for (offset in 0 until count) out.add(data[index + offset])
                    index += count
                }
                marker < 0x80 -> out.add(marker.toByte())
                marker >= 0xC0 -> {
                    if (out.size < limit) out.add(' '.code.toByte())
                    if (out.size < limit) out.add((marker xor 0x80).toByte())
                }
                else -> {
                    if (index >= data.size) break
                    val next = data[index].toInt() and 0xFF
                    index++
                    val token = (marker shl 8) or next
                    val distance = (token shr 3) and 0x07FF
                    val length = (token and 0x07) + 3
                    if (distance <= 0 || distance > out.size) continue
                    val copies = minOf(length, limit - out.size)
                    repeat(copies) { out.add(out.bytes[out.size - distance]) }
                }
            }
        }
        return out.toByteArray()
    }

    private class GrowingBytes(capacity: Int) {
        var bytes = ByteArray(capacity.coerceAtLeast(1))
        var size = 0

        fun add(value: Byte) {
            if (size == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
            bytes[size++] = value
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)
    }
}
