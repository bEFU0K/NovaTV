package com.srep.novatv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(val code: Long, val name: String, val url: String, val sha256: String, val notes: String)

/**
 * Обновление самого лаунчера: update.json {versionCode, versionName, url, sha256, notes}.
 * Проверяются: https, SHA-256, совпадение applicationId; подпись проверяет сам Android
 * (обновление принимается только с тем же ключом подписи).
 * Обновление системного образа Android из лаунчера невозможно — это делается на уровне прошивки (OTA/recovery).
 */
object Updater {
    private fun http(url: String): HttpURLConnection {
        if (!url.startsWith("https://")) throw IOException("Разрешены только https-адреса")
        return (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 20_000 }
    }

    suspend fun check(url: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val c = http(url)
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        c.disconnect()
        val code = j.getLong("versionCode")
        if (code <= BuildConfig.VERSION_CODE) null
        else UpdateInfo(code, j.optString("versionName"), j.getString("url"), j.getString("sha256").lowercase(), j.optString("notes"))
    }

    suspend fun download(c: Context, u: UpdateInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        if (u.sha256.length != 64) throw IOException("Сервер не указал корректную контрольную сумму — обновление отклонено")
        val dir = File(c.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val f = File(dir, "nova-update.apk")
        val con = http(u.url)
        val total = con.contentLength.toLong()
        val md = MessageDigest.getInstance("SHA-256")
        var done = 0L
        con.inputStream.use { i ->
            f.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n); md.update(buf, 0, n); done += n
                    if (total > 0) onProgress((done * 100 / total).toInt())
                }
            }
        }
        con.disconnect()
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        if (hex != u.sha256) { f.delete(); throw IOException("Контрольная сумма не совпала — файл повреждён или подменён") }
        f
    }
}
