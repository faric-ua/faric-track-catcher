package ua.faric.trackcatcher.capture

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/** Обкладинки зберігаються в пам'яті застосунку (для історії та тегу MP3). */
object Artwork {
    private const val MAX_SIDE = 600

    fun save(context: Context, artist: String, title: String, album: String, bitmap: Bitmap): String? = try {
        val dir = File(context.filesDir, "artwork").apply { mkdirs() }
        val key = "$artist|$title|$album".lowercase().hashCode().toUInt().toString(16)
        val file = File(dir, "$key.jpg")
        if (!file.isFile || file.length() == 0L) {
            val scaled = scale(bitmap)
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (scaled !== bitmap) scaled.recycle()
        }
        file.absolutePath
    } catch (e: Exception) {
        null
    }

    fun readJpeg(path: String?): ByteArray? {
        if (path == null) return null
        val f = File(path)
        return if (f.isFile && f.length() in 1..1_000_000) f.readBytes() else null
    }

    private fun scale(b: Bitmap): Bitmap {
        val side = maxOf(b.width, b.height)
        if (side <= MAX_SIDE) return b
        val k = MAX_SIDE.toFloat() / side
        return Bitmap.createScaledBitmap(b, maxOf(1, (b.width * k).toInt()), maxOf(1, (b.height * k).toInt()), true)
    }
}
