package com.srep.novatv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipFile

class ApkException(msg: String) : Exception(msg)

data class ApkInfo(
    val files: List<File>, val label: String, val pkg: String, val versionName: String, val versionCode: Long,
    val size: Long, val minSdk: Int, val permissions: List<String>, val abiOk: Boolean, val abiText: String,
    val sdkOk: Boolean, val risky: List<String>, val installedVersion: String?
)

/** Результат установки от системы (текст для пользователя). */
data class InstallResult(val ok: Boolean, val msg: String)

object InstallEvents {
    val message = MutableStateFlow<String?>(null)
    /** Итог по каждой сессии установки (sessionId → результат). Нужен, чтобы ждать конец установки. */
    val results = MutableStateFlow<Map<Int, InstallResult>>(emptyMap())
}

object ApkInstaller {

    fun canInstall(c: Context) = c.packageManager.canRequestPackageInstalls()

    fun inspectUri(c: Context, uri: Uri): ApkInfo {
        val name = c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "package.apk"
        val dir = File(c.cacheDir, "apk").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "in_" + name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        c.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            ?: throw ApkException("Не удалось открыть файл")
        return inspect(c, f, name)
    }

    fun inspect(c: Context, src: File, name: String): ApkInfo {
        val lower = name.lowercase()
        val parts: List<File> = when {
            lower.endsWith(".apk") -> listOf(src)
            lower.endsWith(".apks") || lower.endsWith(".xapk") || lower.endsWith(".apkm") -> unpack(c, src)
            else -> throw ApkException("Неподдерживаемый файл. Нужен .apk, .apks, .xapk или .apkm")
        }
        if (parts.isEmpty()) throw ApkException("В пакете нет APK-файлов")
        val base = parts.firstOrNull { it.name.equals("base.apk", true) } ?: parts.maxByOrNull { it.length() }!!
        val pm = c.packageManager
        val pi = pm.getPackageArchiveInfo(base.absolutePath, PackageManager.GET_PERMISSIONS)
            ?: throw ApkException("Файл повреждён или не является Android-приложением")
        val ai = pi.applicationInfo ?: throw ApkException("В пакете нет данных приложения")
        ai.sourceDir = base.absolutePath
        ai.publicSourceDir = base.absolutePath

        val abis = HashSet<String>()
        try {
            for (p in parts) ZipFile(p).use { z ->
                for (e in z.entries()) {
                    if (e.name.startsWith("lib/")) {
                        val seg = e.name.split('/')
                        if (seg.size > 2) abis.add(seg[1])
                    }
                }
            }
        } catch (e: ZipException) {
            throw ApkException("Архив повреждён")
        }
        val abiOk = Util.abiCompatible(abis, Build.SUPPORTED_ABIS)
        val abiText = if (abis.isEmpty()) "Без нативного кода — подходит всем устройствам"
        else "ABI пакета: ${abis.joinToString()}; устройство: ${Build.SUPPORTED_ABIS.joinToString()}"
        val perms = pi.requestedPermissions?.toList() ?: emptyList()
        val installed = try { pm.getPackageInfo(pi.packageName, 0).versionName } catch (e: Exception) { null }
        return ApkInfo(
            parts, ai.loadLabel(pm).toString(), pi.packageName, pi.versionName ?: "?",
            PackageInfoCompat.getLongVersionCode(pi), parts.sumOf { it.length() }, ai.minSdkVersion, perms,
            abiOk, abiText, ai.minSdkVersion <= Build.VERSION.SDK_INT, Util.risky(perms), installed
        )
    }

    private fun unpack(c: Context, src: File): List<File> {
        val dir = File(c.cacheDir, "bundle").apply { deleteRecursively(); mkdirs() }
        val out = ArrayList<File>()
        try {
            ZipFile(src).use { z ->
                for (e in z.entries()) {
                    if (e.isDirectory || !e.name.lowercase().endsWith(".apk")) continue
                    val f = File(dir, File(e.name).name) // только имя файла — защита от zip-slip
                    z.getInputStream(e).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    out.add(f)
                }
            }
        } catch (e: ZipException) {
            throw ApkException("Архив повреждён")
        }
        return out
    }

    /** Установка через стандартный PackageInstaller — система сама покажет окно подтверждения. */
    fun install(c: Context, info: ApkInfo): Int {
        InstallEvents.message.value = null
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(info.pkg)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            for (f in info.files) {
                f.inputStream().use { ins ->
                    s.openWrite(f.name, 0, f.length()).use { out ->
                        ins.copyTo(out)
                        s.fsync(out)
                    }
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pend = PendingIntent.getBroadcast(c, id, Intent(c, InstallReceiver::class.java), flags)
            s.commit(pend.intentSender)
        }
        return id
    }

    /** Поиск .apk/.apks на доступных накопителях (USB) и в «Загрузках». Может быть пусто из-за ограничений Android — тогда используйте «Выбрать файл». */
    fun scan(): List<File> {
        val roots = ArrayList<File>()
        File("/storage").listFiles()?.filter { it.name != "self" && it.name != "emulated" }?.let { roots.addAll(it) }
        roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        val res = ArrayList<File>()
        fun walk(d: File, depth: Int) {
            val list = d.listFiles() ?: return
            for (f in list) {
                if (f.isDirectory && depth < 3) walk(f, depth + 1)
                else if (f.isFile) {
                    val n = f.name.lowercase()
                    if (n.endsWith(".apk") || n.endsWith(".apks") || n.endsWith(".xapk") || n.endsWith(".apkm")) res.add(f)
                }
            }
        }
        roots.forEach { walk(it, 0) }
        return res.take(200)
    }
}

class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(c: Context, i: Intent) {
        val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sid = i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        fun finish(ok: Boolean, text: String) {
            InstallEvents.message.value = text
            if (sid >= 0) InstallEvents.results.update { it + (sid to InstallResult(ok, text)) }
        }
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val conf = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (conf != null) {
                    conf.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { c.startActivity(conf) } catch (e: Exception) {
                        AppLog.w("install", "confirm", e)
                        finish(false, "Не удалось открыть окно подтверждения установки")
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> finish(true, "Готово: приложение установлено")
            PackageInstaller.STATUS_FAILURE_ABORTED -> finish(false, "Установка отменена")
            PackageInstaller.STATUS_FAILURE_BLOCKED -> finish(false, "Установка заблокирована системой или политикой устройства")
            PackageInstaller.STATUS_FAILURE_CONFLICT -> finish(false, "Конфликт: подпись не совпадает с установленной версией — сначала удалите её")
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> finish(false, "Приложение несовместимо с этим устройством")
            PackageInstaller.STATUS_FAILURE_INVALID -> finish(false, "Файл повреждён или некорректен")
            PackageInstaller.STATUS_FAILURE_STORAGE -> finish(false, "Недостаточно места на устройстве")
            else -> {
                AppLog.w("install", "status=$status msg=$msg")
                finish(false, "Ошибка установки: ${msg ?: "код $status"}")
            }
        }
    }
}
