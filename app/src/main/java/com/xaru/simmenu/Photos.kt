package com.xaru.simmenu

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.security.PrivateKey
import kotlin.math.max
import kotlin.math.min

/** Кэш превью в памяти. Очищается при закрытии сейфа. */
object ThumbCache {
    private val c = object : LruCache<String, Pair<Bitmap?, Long>>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Pair<Bitmap?, Long>): Int = value.first?.byteCount ?: 1
    }
    fun get(key: String): Pair<Bitmap?, Long>? = c.get(key)
    fun put(key: String, v: Pair<Bitmap?, Long>) { if (v.first != null) c.put(key, v) }
    fun clear() = c.evictAll()
}

object Photos {
    private val gate = Semaphore(2)

    private fun readAll(file: File, priv: PrivateKey): ByteArray =
        VaultReader(file, priv).use { r ->
            require(r.length in 1..150_000_000L) { "bad size" }
            val out = ByteArray(r.length.toInt())
            var pos = 0
            while (pos < out.size) {
                val n = r.read(pos.toLong(), out, pos, min(256 * 1024, out.size - pos))
                if (n <= 0) break
                pos += n
            }
            out
        }

    private fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

        val orient = try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val m = Matrix()
        when (orient) {
            2 -> m.postScale(-1f, 1f)
            3 -> m.postRotate(180f)
            4 -> m.postScale(1f, -1f)
            5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.postRotate(90f)
            7 -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            8 -> m.postRotate(270f)
            else -> return bmp
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    /** Полноэкранный просмотр. */
    suspend fun load(file: File, priv: PrivateKey, maxSide: Int = 3000): Bitmap? = gate.withPermit {
        withContext(Dispatchers.IO) {
            try { decode(readAll(file, priv), maxSide) } catch (_: Throwable) { null }
        }
    }

    suspend fun thumb(file: File, priv: PrivateKey): Pair<Bitmap?, Long> = gate.withPermit {
        withContext(Dispatchers.IO) {
            try { Pair(decode(readAll(file, priv), 480), 0L) } catch (_: Throwable) { Pair(null, 0L) }
        }
    }

    /** Расшифровать фото во временный файл с правильным расширением (для отправки). Возвращает файл и mime. */
    fun decryptForShare(file: File, priv: PrivateKey, dir: File): Pair<File, String> {
        val bytes = readAll(file, priv)
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        val ext: String
        val mime: String
        when {
            at(0) == 0xFF && at(1) == 0xD8 -> { ext = "jpg"; mime = "image/jpeg" }
            at(0) == 0x89 && at(1) == 0x50 -> { ext = "png"; mime = "image/png" }
            at(0) == 0x52 && at(1) == 0x49 && at(8) == 0x57 -> { ext = "webp"; mime = "image/webp" }
            at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70 -> { ext = "heic"; mime = "image/heic" }
            else -> { ext = "jpg"; mime = "image/jpeg" }
        }
        val out = File(dir, "photo_${System.currentTimeMillis()}.$ext")
        out.writeBytes(bytes)
        return Pair(out, mime)
    }
}
