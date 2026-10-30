package com.xaru.simmenu

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/** Приватный ключ живёт в памяти только пока сейф открыт. */
object Session {
    @Volatile var priv: PrivateKey? = null
}

/**
 * Схема защиты:
 *  - при создании сейфа генерируется пара RSA-2048;
 *  - приватный ключ шифруется AES-GCM ключом из пароля (PBKDF2, 200 000 итераций) и лежит в vault.key;
 *  - публичный ключ лежит открыто, поэтому фоновая очередь может шифровать видео без пароля;
 *  - у каждого видео свой случайный AES-256 ключ (режим CTR), он завёрнут публичным ключом в заголовке файла;
 *  - прочитать видео можно только после ввода пароля.
 */
object VaultCrypto {
    const val HEADER = 288
    internal const val MAGIC = 0x534D5632
    private const val KEY_MAGIC = 0x534D4B31
    private const val ITER = 200_000
    private val rnd = SecureRandom()

    fun keyFile(ctx: Context) = File(ctx.filesDir, "vault.key")
    fun dir(ctx: Context) = File(ctx.filesDir, "vault").apply { mkdirs() }
    fun hasKey(ctx: Context) = keyFile(ctx).exists()
    fun isPhoto(f: File) = f.extension == "vlp"

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.extension == "vlt" || f.extension == "vlp" }?.sortedByDescending { it.lastModified() } ?: emptyList()

    // ---------- ключи ----------
    private class KeyData(val iter: Int, val salt: ByteArray, val iv: ByteArray, val pub: ByteArray, val wrapped: ByteArray)

    private fun derive(pass: CharArray, salt: ByteArray, iter: Int): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(f.generateSecret(PBEKeySpec(pass, salt, iter, 256)).encoded, "AES")
    }

    private fun oaep() = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    private fun writeKeyFile(file: File, pass: CharArray, privBytes: ByteArray, pubBytes: ByteArray) {
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, derive(pass, salt, ITER), GCMParameterSpec(128, iv))
        val wrapped = c.doFinal(privBytes)
        val tmp = File(file.path + ".tmp")
        DataOutputStream(FileOutputStream(tmp)).use { o ->
            o.writeInt(KEY_MAGIC); o.writeInt(ITER)
            o.writeInt(salt.size); o.write(salt)
            o.writeInt(iv.size); o.write(iv)
            o.writeInt(pubBytes.size); o.write(pubBytes)
            o.writeInt(wrapped.size); o.write(wrapped)
        }
        if (!tmp.renameTo(file)) { tmp.copyTo(file, true); tmp.delete() }
    }

    private fun readKeyFile(f: File): KeyData? = try {
        DataInputStream(BufferedInputStream(FileInputStream(f))).use { i ->
            if (i.readInt() != KEY_MAGIC) null else {
                val iter = i.readInt()
                val salt = ByteArray(i.readInt()).also { i.readFully(it) }
                val iv = ByteArray(i.readInt()).also { i.readFully(it) }
                val pub = ByteArray(i.readInt()).also { i.readFully(it) }
                val wrapped = ByteArray(i.readInt()).also { i.readFully(it) }
                KeyData(iter, salt, iv, pub, wrapped)
            }
        }
    } catch (_: Exception) { null }

    fun create(ctx: Context, pass: CharArray): PrivateKey {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        writeKeyFile(keyFile(ctx), pass, kp.private.encoded, kp.public.encoded)
        return kp.private
    }

    fun unlock(ctx: Context, pass: CharArray, file: File = keyFile(ctx)): PrivateKey? {
        val kd = readKeyFile(file) ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, derive(pass, kd.salt, kd.iter), GCMParameterSpec(128, kd.iv))
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(c.doFinal(kd.wrapped)))
        } catch (_: Exception) { null }
    }

    fun changePassword(ctx: Context, old: CharArray, new: CharArray): Boolean {
        val kd = readKeyFile(keyFile(ctx)) ?: return false
        val priv = unlock(ctx, old) ?: return false
        writeKeyFile(keyFile(ctx), new, priv.encoded, kd.pub)
        return true
    }

    fun publicKey(ctx: Context): PublicKey {
        val kd = readKeyFile(keyFile(ctx)) ?: throw IllegalStateException("no key")
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(kd.pub))
    }

    // ---------- шифрование файла ----------
    fun encryptTo(ctx: Context, input: InputStream, pub: PublicKey, photo: Boolean = false): File {
        val fkey = ByteArray(32).also { rnd.nextBytes(it) }
        val iv = ByteArray(16).also { rnd.nextBytes(it) }
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        rsa.init(Cipher.ENCRYPT_MODE, pub, oaep())
        val wrapped = rsa.doFinal(fkey)

        val out = File(dir(ctx), (if (photo) "img_" else "vid_") + "${System.currentTimeMillis()}_${rnd.nextInt(10000)}" + (if (photo) ".vlp" else ".vlt"))
        val tmp = File(out.path + ".part")
        FileOutputStream(tmp).use { fos ->
            val header = ByteArray(HEADER)
            ByteBuffer.wrap(header).putInt(MAGIC).put(iv).putShort(wrapped.size.toShort()).put(wrapped)
            fos.write(header)
            val c = Cipher.getInstance("AES/CTR/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fkey, "AES"), IvParameterSpec(iv))
            val co = CipherOutputStream(fos, c)
            input.copyTo(co, 256 * 1024)
            co.flush()
            co.close()
        }
        fkey.fill(0)
        if (!tmp.renameTo(out)) { tmp.copyTo(out, true); tmp.delete() }
        return out
    }

    /** Расшифровать в обычный файл (для отправки и пересжатия). */
    fun decryptTo(file: File, priv: PrivateKey, out: File) {
        VaultReader(file, priv).use { r ->
            FileOutputStream(out).use { os ->
                val buf = ByteArray(256 * 1024)
                var pos = 0L
                while (true) {
                    val n = r.read(pos, buf, 0, buf.size)
                    if (n <= 0) break
                    os.write(buf, 0, n)
                    pos += n
                }
            }
        }
    }

    // ---------- резервная копия ----------
    fun backup(ctx: Context, out: OutputStream, onProgress: (Long, Long) -> Unit) {
        val files = list(ctx)
        val total = files.sumOf { it.length() }
        var done = 0L
        ZipOutputStream(BufferedOutputStream(out)).use { z ->
            z.setLevel(Deflater.NO_COMPRESSION)
            z.putNextEntry(ZipEntry("vault.key"))
            FileInputStream(keyFile(ctx)).use { it.copyTo(z) }
            z.closeEntry()
            val buf = ByteArray(256 * 1024)
            for (f in files) {
                z.putNextEntry(ZipEntry("vault/${f.name}"))
                FileInputStream(f).use { i ->
                    while (true) {
                        val n = i.read(buf)
                        if (n <= 0) break
                        z.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
                z.closeEntry()
            }
        }
    }

    /** Возвращает приватный ключ при успехе, null если пароль не подошёл или файл повреждён. */
    fun restore(ctx: Context, input: InputStream, pass: CharArray): PrivateKey? {
        val stage = File(ctx.cacheDir, "restore")
        stage.deleteRecursively(); stage.mkdirs()
        try {
            ZipInputStream(BufferedInputStream(input)).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val dest = when {
                        e.name == "vault.key" -> File(stage, "vault.key")
                        e.name.startsWith("vault/") && (e.name.endsWith(".vlt") || e.name.endsWith(".vlp")) -> File(stage, File(e.name).name)
                        else -> null
                    }
                    if (dest != null) FileOutputStream(dest).use { z.copyTo(it, 256 * 1024) }
                    z.closeEntry()
                    e = z.nextEntry
                }
            }
            val keyInStage = File(stage, "vault.key")
            val priv = unlock(ctx, pass, keyInStage) ?: return null

            dir(ctx).listFiles()?.forEach { it.delete() }
            stage.listFiles()?.forEach { f ->
                val dest = if (f.name == "vault.key") keyFile(ctx) else File(dir(ctx), f.name)
                if (!f.renameTo(dest)) { f.copyTo(dest, true); f.delete() }
            }
            return priv
        } finally {
            stage.deleteRecursively()
        }
    }
}

// ---------- чтение с произвольным доступом ----------
class VaultReader(file: File, priv: PrivateKey) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val key: SecretKeySpec
    private val iv = ByteArray(16)
    val length: Long

    init {
        val h = ByteArray(VaultCrypto.HEADER)
        raf.readFully(h)
        val bb = ByteBuffer.wrap(h)
        require(bb.int == VaultCrypto.MAGIC) { "bad file" }
        bb.get(iv)
        val n = bb.short.toInt() and 0xFFFF
        val wrapped = ByteArray(n)
        bb.get(wrapped)
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        rsa.init(Cipher.DECRYPT_MODE, priv,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
        key = SecretKeySpec(rsa.doFinal(wrapped), "AES")
        length = raf.length() - VaultCrypto.HEADER
    }

    private fun ivPlus(blocks: Long): ByteArray {
        val out = iv.clone()
        var carry = blocks
        var i = 15
        while (carry != 0L && i >= 0) {
            val sum = (out[i].toInt() and 0xFF) + (carry and 0xFF).toInt()
            out[i] = sum.toByte()
            carry = (carry ushr 8) + (sum ushr 8)
            i--
        }
        return out
    }

    @Synchronized
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= length) return -1
        val n = min(len.toLong(), length - pos).toInt()
        if (n <= 0) return 0
        val tmp = ByteArray(n)
        raf.seek(VaultCrypto.HEADER + pos)
        raf.readFully(tmp, 0, n)
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(ivPlus(pos / 16)))
        val skip = (pos % 16).toInt()
        if (skip > 0) c.update(ByteArray(skip))
        c.update(tmp, 0, n, buf, off)
        return n
    }

    override fun close() { try { raf.close() } catch (_: Exception) {} }
}

/** Для MediaMetadataRetriever / MediaExtractor. */
class VaultMediaSource(private val r: VaultReader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        r.read(position, buffer, offset, size)
    override fun getSize(): Long = r.length
    override fun close() {}
}

/** Для ExoPlayer: расшифровка «на лету», без временных файлов. */
@androidx.annotation.OptIn(UnstableApi::class)
class VaultDataSource(private val reader: VaultReader) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var pos = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        pos = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) reader.length - pos else dataSpec.length
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT
        val n = reader.read(pos, buffer, offset, min(length.toLong(), remaining).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        pos += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        if (opened) { opened = false; transferEnded() }
    }
}
