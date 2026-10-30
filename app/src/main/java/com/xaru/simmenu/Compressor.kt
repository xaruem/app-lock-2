package com.xaru.simmenu

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.PrivateKey
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

enum class Quality(val label: String, val factor: Float) {
    BEST("Макс. качество", 0.65f),
    BALANCE("Баланс", 0.45f),
    SMALL("Меньше размер", 0.30f)
}

/** px = короткая сторона кадра (1080 для 1920x1080 и для 1080x1920). 0 = без изменений. */
enum class Resolution(val label: String, val px: Int) {
    ORIGINAL("Оригинал", 0),
    P1440("1440p", 1440),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480)
}

enum class Fps(val label: String, val fps: Int) {
    ORIGINAL("Оригинал", 0),
    F30("30 fps", 30),
    F24("24 fps", 24)
}

/** factor = доля от исходного битрейта (0.15–1.0). */
data class Settings(val factor: Float = 0.65f, val resPx: Int = 0, val fps: Int = 0)

/** endMs = 0 значит «без обрезки». */
data class Trim(val startMs: Long = 0L, val endMs: Long = 0L)

data class VideoInfo(
    val uri: Uri,
    val vaultName: String?,
    val size: Long,
    val isHevc: Boolean,
    val bitrate: Int,
    val fps: Int,
    val durationMs: Long,
    val w: Int,   // с учётом поворота
    val h: Int
) {
    val key: String get() = vaultName ?: uri.toString()
}

data class Plan(
    val transcode: Boolean,
    val outW: Int,
    val outH: Int,
    val bitrate: Int,
    val estSize: Long,
    val downscale: Boolean,
    val dropFps: Boolean,
    val trimStart: Long,
    val trimEnd: Long
)

class CompareResult(
    val orig: Bitmap?,
    val comp: Bitmap?,
    val note: String
)

object Frames {
    private val gate = Semaphore(2)

    fun grab(r: MediaMetadataRetriever, timeUs: Long, maxW: Int, option: Int): Bitmap? {
        val src = r.getFrameAtTime(timeUs, option) ?: return null
        if (src.width <= maxW) return src
        val th = (maxW.toLong() * src.height / src.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, maxW, th, true)
        if (scaled !== src) src.recycle()
        return scaled
    }

    /** Кадр из сейфа (для превью в сетке). */
    suspend fun vaultThumb(file: File, priv: PrivateKey): Pair<Bitmap?, Long> = gate.withPermit {
        withContext(Dispatchers.IO) {
            var reader: VaultReader? = null
            val r = MediaMetadataRetriever()
            try {
                reader = VaultReader(file, priv)
                r.setDataSource(VaultMediaSource(reader))
                val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                Pair(grab(r, 0, 480, MediaMetadataRetriever.OPTION_CLOSEST_SYNC), d)
            } catch (_: Exception) {
                Pair(null, 0L)
            } finally {
                try { r.release() } catch (_: Exception) {}
                reader?.close()
            }
        }
    }
}

object Compressor {

    private fun even(v: Float): Int = ((v / 2f).roundToInt() * 2).coerceAtLeast(2)

    private fun trimActive(t: Trim, dur: Long) = t.endMs > 0 && (t.startMs > 300 || t.endMs < dur - 300)

    /** Что будет сделано с видео и сколько оно примерно будет весить. */
    fun plan(i: VideoInfo, s: Settings, t: Trim): Plan {
        val trimmed = trimActive(t, i.durationMs)
        val durMs = if (trimmed) t.endMs - t.startMs else i.durationMs
        val shortSide = min(i.w, i.h)
        val downscale = s.resPx > 0 && shortSide > s.resPx
        val dropFps = s.fps > 0 && i.fps > s.fps + 1
        // у H.265-источника выигрыш меньше, поэтому не режем битрейт так сильно
        val eff = if (i.isHevc) min(1f, s.factor * 1.5f) else s.factor
        val needs = !i.isHevc || downscale || trimmed || dropFps || eff < 0.9f
        val ts = if (trimmed) t.startMs else 0L
        val te = if (trimmed) t.endMs else 0L
        if (!needs) return Plan(false, i.w, i.h, i.bitrate, i.size, false, false, 0, 0)

        var ow = i.w
        var oh = i.h
        if (downscale) {
            val k = s.resPx.toFloat() / shortSide
            ow = even(i.w * k); oh = even(i.h * k)
        }
        val pix = if (i.w > 0 && i.h > 0) (ow.toDouble() * oh) / (i.w.toDouble() * i.h) else 1.0
        val fpsRatio = if (dropFps) s.fps.toDouble() / i.fps else 1.0
        val br = if (i.bitrate > 0) {
            val raw = (i.bitrate * eff * pix.pow(0.9) * fpsRatio.pow(0.75)).toInt()
            raw.coerceIn(min(500_000, i.bitrate), i.bitrate)
        } else 0
        val sizeScale = if (i.durationMs > 0) durMs.toDouble() / i.durationMs else 1.0
        val cap = (i.size * sizeScale).toLong()
        val est = if (br > 0 && durMs > 0) min(cap, (durMs / 1000.0 * br / 8.0).toLong()) else cap
        return Plan(true, ow, oh, br, est, downscale, dropFps, ts, te)
    }

    // ---------- чтение параметров ----------
    private fun probeCore(
        uri: Uri, vaultName: String?, size: Long,
        setExtractor: (MediaExtractor) -> Unit,
        setRetriever: (MediaMetadataRetriever) -> Unit
    ): VideoInfo? {
        var hevc = false
        var fps = 0
        val ex = MediaExtractor()
        try {
            setExtractor(ex)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    hevc = mime == MediaFormat.MIMETYPE_VIDEO_HEVC
                    fps = try { f.getInteger(MediaFormat.KEY_FRAME_RATE) } catch (_: Exception) {
                        try { f.getFloat(MediaFormat.KEY_FRAME_RATE).roundToInt() } catch (_: Exception) { 0 }
                    }
                    break
                }
            }
        } catch (_: Exception) {
        } finally { try { ex.release() } catch (_: Exception) {} }

        var br = 0; var dur = 0L; var w = 0; var h = 0; var rot = 0
        val r = MediaMetadataRetriever()
        try {
            setRetriever(r)
            br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            return null
        } finally { try { r.release() } catch (_: Exception) {} }

        if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
        if (br == 0 && dur > 0 && size > 0) br = (size * 8000L / dur).toInt()
        return VideoInfo(uri, vaultName, size, hevc, br, fps, dur, w, h)
    }

    fun probe(ctx: Context, uri: Uri): VideoInfo? {
        val size = try {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        } catch (_: Exception) { 0L }
        return probeCore(uri, null, size,
            { it.setDataSource(ctx, uri, null) },
            { it.setDataSource(ctx, uri) })
    }

    fun probeVault(ctx: Context, file: File, priv: PrivateKey): VideoInfo? {
        var reader: VaultReader? = null
        return try {
            reader = VaultReader(file, priv)
            val rd = reader
            probeCore(Uri.parse("vault://${file.name}"), file.name, file.length() - VaultCrypto.HEADER,
                { it.setDataSource(VaultMediaSource(rd)) },
                { it.setDataSource(VaultMediaSource(rd)) })
        } catch (_: Exception) { null } finally { reader?.close() }
    }

    /** Кадр из исходного видео (галерея или сейф) для обрезки. */
    fun frameAt(
        ctx: Context, info: VideoInfo, ms: Long, maxW: Int = 480,
        option: Int = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
    ): Bitmap? {
        val r = MediaMetadataRetriever()
        var reader: VaultReader? = null
        return try {
            if (info.vaultName != null) {
                val priv = Session.priv ?: return null
                reader = VaultReader(File(VaultCrypto.dir(ctx), info.vaultName), priv)
                r.setDataSource(VaultMediaSource(reader))
            } else r.setDataSource(ctx, info.uri)
            Frames.grab(r, ms * 1000, maxW, option)
        } catch (_: Exception) { null } finally {
            try { r.release() } catch (_: Exception) {}
            reader?.close()
        }
    }

    // ---------- перекодирование ----------
    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun transform(
        ctx: Context, uri: Uri, out: File, plan: Plan, s: Settings,
        clipStartMs: Long, clipEndMs: Long, onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Boolean> { cont ->
            val handler = Handler(Looper.getMainLooper())
            val holder = ProgressHolder()

            val settings = VideoEncoderSettings.Builder().apply {
                if (plan.bitrate > 0) setBitrate(plan.bitrate)
            }.build()
            val encoderFactory = DefaultEncoderFactory.Builder(ctx)
                .setRequestedVideoEncoderSettings(settings)
                .setEnableFallback(true)
                .build()

            val effects = mutableListOf<Effect>()
            if (plan.dropFps) effects.add(FrameDropEffect.createDefaultFrameDropEffect(s.fps.toFloat()))
            if (plan.downscale) effects.add(
                Presentation.createForWidthAndHeight(plan.outW, plan.outH, Presentation.LAYOUT_SCALE_TO_FIT)
            )

            val mib = MediaItem.Builder().setUri(uri)
            if (clipEndMs > 0) {
                mib.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clipStartMs)
                        .setEndPositionMs(clipEndMs)
                        .build()
                )
            }
            val edited = EditedMediaItem.Builder(mib.build())
                .setEffects(Effects(emptyList(), effects))
                .build()

            lateinit var transformer: Transformer
            val poll = object : Runnable {
                override fun run() {
                    if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                        onProgress(holder.progress)
                    }
                    handler.postDelayed(this, 500)
                }
            }

            transformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H265)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        handler.removeCallbacks(poll)
                        out.delete()
                        if (cont.isActive) cont.resume(false)
                    }
                })
                .build()

            cont.invokeOnCancellation {
                handler.removeCallbacks(poll)
                handler.post { try { transformer.cancel() } catch (_: Exception) {} }
                out.delete()
            }
            transformer.start(edited, out.absolutePath)
            handler.post(poll)
        }
    }

    /** Тестовый фрагмент 3 секунды с выбранными настройками: кадр до и после. */
    suspend fun preview(ctx: Context, info: VideoInfo, s: Settings): CompareResult {
        val plan = plan(info, s, Trim())
        if (!plan.transcode) {
            return CompareResult(null, null, "Для этих настроек видео копируется без изменений: сравнивать нечего.")
        }
        val dur = info.durationMs
        val start = (dur / 2 - 1500).coerceAtLeast(0L)
        val end = min(dur, start + 3000)
        val tmp = File(File(ctx.cacheDir, "work").apply { mkdirs() }, "preview_${System.currentTimeMillis()}.mp4")
        try {
            val ok = transform(ctx, info.uri, tmp, plan, s, start, end) {}
            if (!ok) return CompareResult(null, null, "Не удалось сделать тестовый фрагмент.")
            return withContext(Dispatchers.IO) {
                val mid = (end - start) / 2
                val orig = frameAt(ctx, info, start + mid, 720, MediaMetadataRetriever.OPTION_CLOSEST)
                var comp: Bitmap? = null
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(tmp.absolutePath)
                    comp = Frames.grab(r, mid * 1000, 720, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (_: Exception) {
                } finally { try { r.release() } catch (_: Exception) {} }
                val origPart = if (dur > 0) info.size * (end - start) / dur else 0L
                CompareResult(orig, comp, "Фрагмент ${(end - start) / 1000} сек: ≈ ${origPart / 1024} КБ → ${tmp.length() / 1024} КБ")
            }
        } finally {
            tmp.delete()
        }
    }
}
