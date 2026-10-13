package app.moye.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import app.moye.core.importing.ComicArchive
import app.moye.core.model.BookFormat
import app.moye.core.model.EmbeddedCover
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

internal object PdfPages {
    private val lock = Any()

    fun count(file: File): Int = synchronized(lock) {
        if (!file.isFile) return 0
        open(file) { renderer -> renderer.pageCount } ?: 0
    }

    fun render(file: File, index: Int, width: Int, height: Int): Bitmap? = synchronized(lock) {
        if (!file.isFile || width < 1 || height < 1) return null
        open(file) { renderer ->
            if (index !in 0 until renderer.pageCount) return@open null
            renderer.openPage(index).use { page ->
                val scale = min(width.toFloat() / page.width, height.toFloat() / page.height)
                val destWidth = (page.width * scale).toInt().coerceAtLeast(1)
                val destHeight = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(destWidth, destHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                val matrix = Matrix().apply { setScale(scale, scale) }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }

    fun cover(file: File): EmbeddedCover? {
        val bitmap = render(file, 0, 480, 720) ?: return null
        return try {
            val bytes = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bytes)) return null
            EmbeddedCover("image/jpeg", bytes.toByteArray())
        } finally {
            bitmap.recycle()
        }
    }

    private inline fun <T> open(file: File, block: (PdfRenderer) -> T): T? {
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use(block)
            }
        } catch (_: Exception) {
            null
        }
    }
}

internal object ComicBitmaps {
    fun decode(file: File, format: BookFormat, index: Int, maxEdge: Int): Bitmap? {
        val bytes = ComicArchive.read(file, format, index) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge.coerceAtLeast(1))
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        val longest = max(width, height)
        while (longest / sample > maxEdge) sample *= 2
        return sample.coerceAtLeast(1)
    }
}
