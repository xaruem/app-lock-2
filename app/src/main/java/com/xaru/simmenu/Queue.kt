package com.xaru.simmenu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import kotlin.math.max

/** Задание на сжатие. vaultName != null — пересжатие видео, уже лежащего в сейфе. */
data class QJob(val id: String, val uri: String?, val vaultName: String?, val s: Settings, val trim: Trim)

object JobStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "queue.json")

    @Synchronized
    fun read(ctx: Context): List<QJob> = try {
        val arr = JSONArray(file(ctx).readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            QJob(
                o.getString("id"),
                if (o.isNull("uri")) null else o.getString("uri"),
                if (o.isNull("vault")) null else o.getString("vault"),
                Settings(o.getDouble("factor").toFloat(), o.getInt("res"), o.getInt("fps")),
                Trim(o.getLong("ts"), o.getLong("te"))
            )
        }
    } catch (_: Exception) { emptyList() }

    @Synchronized
    private fun write(ctx: Context, list: List<QJob>) {
        val arr = JSONArray()
        list.forEach { j ->
            arr.put(JSONObject().apply {
                put("id", j.id)
                put("uri", j.uri ?: JSONObject.NULL)
                put("vault", j.vaultName ?: JSONObject.NULL)
                put("factor", j.s.factor.toDouble()); put("res", j.s.resPx); put("fps", j.s.fps)
                put("ts", j.trim.startMs); put("te", j.trim.endMs)
            })
        }
        file(ctx).writeText(arr.toString())
    }

    @Synchronized fun add(ctx: Context, jobs: List<QJob>) = write(ctx, read(ctx) + jobs)
    @Synchronized fun remove(ctx: Context, id: String) = write(ctx, read(ctx).filter { it.id != id })
    @Synchronized fun clear(ctx: Context) = write(ctx, emptyList())
}

data class QueueUi(
    val pending: Int = 0,
    val running: Boolean = false,
    val progress: Float = 0f,
    val label: String = "",
    val tick: Int = 0
)

object QueueState {
    val flow = MutableStateFlow(QueueUi())
}

object Stats {
    private fun p(ctx: Context) = ctx.getSharedPreferences("stats", Context.MODE_PRIVATE)
    fun saved(ctx: Context): Long = p(ctx).getLong("saved", 0L)

    @Synchronized fun addSaved(ctx: Context, b: Long) {
        p(ctx).edit().putLong("saved", saved(ctx) + b).apply()
    }

    @Synchronized fun addDelete(ctx: Context, uri: String) {
        val s = HashSet(p(ctx).getStringSet("del", emptySet()) ?: emptySet())
        s.add(uri)
        p(ctx).edit().putStringSet("del", s).apply()
    }

    fun deletes(ctx: Context): Set<String> = HashSet(p(ctx).getStringSet("del", emptySet()) ?: emptySet())
    fun clearDeletes(ctx: Context) { p(ctx).edit().remove("del").apply() }

    fun askedNotif(ctx: Context): Boolean = p(ctx).getBoolean("asked_notif", false)
    fun setAskedNotif(ctx: Context) { p(ctx).edit().putBoolean("asked_notif", true).apply() }
}

object Temp {
    fun shareDir(ctx: Context) = File(ctx.cacheDir, "share").apply { mkdirs() }

    /** Удаляет старые временные файлы (расшифрованные копии для отправки, хвосты после сбоев). */
    fun clean(ctx: Context) {
        val now = System.currentTimeMillis()
        File(ctx.cacheDir, "share").listFiles()?.forEach { if (now - it.lastModified() > 15 * 60_000L) it.delete() }
        File(ctx.cacheDir, "work").listFiles()?.forEach { if (now - it.lastModified() > 6 * 3600_000L) it.delete() }
        VaultCrypto.dir(ctx).listFiles { f -> f.extension == "part" }?.forEach { it.delete() }
    }
}

data class RunResult(val ok: Boolean, val origSize: Long, val newSize: Long, val note: String? = null)

object Pipeline {

    /** Одно задание целиком: исходник → сжатие → шифрование → сейф. Работает и из фона (без пароля). */
    suspend fun run(ctx: Context, job: QJob, onProgress: (Float) -> Unit): RunResult {
        val work = File(ctx.cacheDir, "work").apply { mkdirs() }
        val pub = try { VaultCrypto.publicKey(ctx) } catch (_: Exception) {
            return RunResult(false, 0, 0, "Сейф не создан")
        }
        var srcTemp: File? = null
        var outTemp: File? = null
        try {
            var oldVault: File? = null
            val srcUri: Uri
            if (job.vaultName != null) {
                val priv = Session.priv ?: return RunResult(false, 0, 0, "Сейф закрыт")
                val vf = File(VaultCrypto.dir(ctx), job.vaultName)
                oldVault = vf
                val t = File(work, "src_${job.id}.mp4")
                srcTemp = t
                withContext(Dispatchers.IO) { VaultCrypto.decryptTo(vf, priv, t) }
                srcUri = Uri.fromFile(t)
            } else {
                srcUri = Uri.parse(job.uri)
            }

            val info = withContext(Dispatchers.IO) { Compressor.probe(ctx, srcUri) }
                ?: return RunResult(false, 0, 0, "Не удалось прочитать видео")
            val plan = Compressor.plan(info, job.s, job.trim)

            val newFile: File
            if (!plan.transcode) {
                newFile = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(srcUri)!!.use { VaultCrypto.encryptTo(ctx, it, pub) }
                }
            } else {
                val o = File(work, "out_${job.id}.mp4")
                outTemp = o
                val ok = Compressor.transform(ctx, srcUri, o, plan, job.s, plan.trimStart, plan.trimEnd) { onProgress(it / 100f) }
                if (!ok) return RunResult(false, 0, 0, "Ошибка сжатия")
                val keepOriginal = info.size > 0 && o.length() >= info.size &&
                    plan.trimEnd == 0L && !plan.downscale && !plan.dropFps
                newFile = withContext(Dispatchers.IO) {
                    if (keepOriginal) ctx.contentResolver.openInputStream(srcUri)!!.use { VaultCrypto.encryptTo(ctx, it, pub) }
                    else FileInputStream(o).use { VaultCrypto.encryptTo(ctx, it, pub) }
                }
            }

            val newSize = newFile.length() - VaultCrypto.HEADER
            if (oldVault != null) {
                if (plan.trimEnd > 0L || newFile.length() < oldVault.length()) oldVault.delete()
                else {
                    newFile.delete()
                    return RunResult(true, info.size, info.size, "Не удалось уменьшить размер")
                }
            }
            return RunResult(true, info.size, newSize)
        } finally {
            srcTemp?.delete()
            outTemp?.delete()
        }
    }
}

object Notif {
    const val CH = "compress"
    const val ID = 4201

    fun ensure(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CH, "Обновление данных", NotificationManager.IMPORTANCE_LOW))
        }
    }

    // Текст нарочно нейтральный: в шторке не видно, что это видео.
    fun build(ctx: Context, text: String, pct: Int, indeterminate: Boolean, showProgress: Boolean, ongoing: Boolean): Notification {
        ensure(ctx)
        return NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("SIM-меню")
            .setContentText(text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(if (showProgress) 100 else 0, pct, indeterminate)
            .build()
    }
}

class CompressWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private fun fg(text: String, pct: Int, indeterminate: Boolean): ForegroundInfo {
        val n = Notif.build(applicationContext, text, pct, indeterminate, true, true)
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(Notif.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(Notif.ID, n)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = fg("Обновление данных…", 0, true)

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        try { setForeground(getForegroundInfo()) } catch (_: Exception) { }

        var done = 0
        var failed = 0
        var saved = 0L
        while (true) {
            val jobs = JobStore.read(ctx).filter { it.vaultName == null }
            val job = jobs.firstOrNull() ?: break
            val total = done + failed + jobs.size
            val label = "Видео ${done + failed + 1} из $total"
            QueueState.flow.update { it.copy(pending = jobs.size, running = true, progress = 0f, label = label) }
            nm.notify(Notif.ID, Notif.build(ctx, label, 0, true, true, true))

            val res = try {
                Pipeline.run(ctx, job) { p ->
                    QueueState.flow.update { it.copy(progress = p) }
                    nm.notify(Notif.ID, Notif.build(ctx, label, (p * 100).toInt(), false, true, true))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RunResult(false, 0, 0, e.message)
            }

            JobStore.remove(ctx, job.id)
            if (res.ok) {
                done++
                val s = max(0L, res.origSize - res.newSize)
                saved += s
                Stats.addSaved(ctx, s)
                job.uri?.let { Stats.addDelete(ctx, it) }
            } else failed++
            QueueState.flow.update { it.copy(tick = it.tick + 1) }
        }

        QueueState.flow.update { it.copy(pending = 0, running = false, progress = 0f, label = "") }
        if (done + failed > 0) {
            val txt = "Готово: $done" + (if (failed > 0) ", ошибок: $failed" else "") +
                ". Сэкономлено ${Formatter.formatFileSize(ctx, saved)}"
            nm.notify(Notif.ID + 1, Notif.build(ctx, txt, 0, false, false, false))
        }
        return Result.success()
    }
}

object QueueRunner {
    private const val NAME = "compress-queue"

    /** Вызывать не из главного потока. */
    fun kick(ctx: Context, chargingOnly: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        val running = wm.getWorkInfosForUniqueWork(NAME).get().any { it.state == WorkInfo.State.RUNNING }
        if (running) return
        val b = OneTimeWorkRequestBuilder<CompressWorker>()
            .setConstraints(Constraints.Builder().setRequiresCharging(chargingOnly).build())
        if (!chargingOnly) b.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        wm.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, b.build())
    }

    fun clear(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
        JobStore.clear(ctx)
        QueueState.flow.value = QueueUi()
    }
}
