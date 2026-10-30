package com.srep.novatv

import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ProvItem(
    val id: String, val name: String, val pkg: String, val ext: String,
    val size: Long, val sha: String, val minSdk: Int, val abis: List<String>
)

enum class PState(val text: String) {
    Wait("Ожидает"), Download("Скачивание"), Verify("Проверка"), Install("Установка"),
    Done("Готово"), Skipped("Не подходит ТВ"), Error("Ошибка")
}

class ProvRow(val item: ProvItem) {
    var state by mutableStateOf(PState.Wait)
    var progress by mutableIntStateOf(0)
    var note by mutableStateOf("")
}

/**
 * Первичная «прошивка» приложений: TAPK (вшит в Nova) + остальные приложения, которые скачиваются с сервера Srepportal
 * при первом запуске. Проверяется SHA-256, установка идёт через системный установщик Android.
 */
object Provision {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val rows = MutableStateFlow<List<ProvRow>>(emptyList())
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("")

    private val base get() = BuildConfig.PORTAL_URL.trimEnd('/')

    val tapkItem = ProvItem("tapk", "TAPK — установщик XAPK", Protection.TAPK_PKG, "apk", 0L, "", 21, emptyList())

    fun parse(json: String): List<ProvItem> {
        val a = JSONObject(json).getJSONArray("apps")
        return (0 until a.length()).map { n ->
            val o = a.getJSONObject(n)
            val abis = o.optJSONArray("abis")?.let { x -> (0 until x.length()).map { j -> x.getString(j) } } ?: emptyList()
            ProvItem(
                o.getString("id"), o.getString("name"), o.getString("pkg"), o.optString("ext", "apk"),
                o.getLong("size"), o.getString("sha256").lowercase(), o.optInt("min_sdk", 1), abis
            )
        }
    }

    fun cached(): List<ProvItem> = try { Prefs.manifest?.let { parse(it) } ?: emptyList() } catch (e: Exception) { emptyList() }

    /** Что из системных приложений сейчас не установлено (для автоматического восстановления). */
    fun missing(c: Context): List<ProvItem> {
        val skip = Prefs.skippedIds
        val list = ArrayList<ProvItem>()
        if (!Protection.isInstalled(c, Protection.TAPK_PKG)) list.add(tapkItem)
        cached().filter { it.id !in skip && !Protection.isInstalled(c, it.pkg) }.forEach { list.add(it) }
        return list
    }

    private fun http(path: String, token: String): HttpURLConnection {
        if (!base.startsWith("https://")) throw IOException("Адрес Srepportal должен начинаться с https://")
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", NOVA_UA)
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("X-Nova-Token", token)
        return c
    }

    private fun token(): String = SessionStore.flow.value?.access ?: throw IOException("Нужно войти в Srepportal по QR")

    private fun fetchManifest(): String {
        val c = http("/api/tv/apps.php", token())
        c.setRequestProperty("Accept", "application/json")
        val code = c.responseCode
        if (code == 401) { c.disconnect(); throw IOException("Сессия устарела — войдите по QR заново") }
        if (code == 404) { c.disconnect(); throw IOException("На сервере нет /api/tv/apps.php — загрузите серверный zip") }
        if (code !in 200..299) { c.disconnect(); throw IOException("Сервер вернул ошибку $code") }
        val txt = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return txt
    }

    fun start(c: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = c.applicationContext
        scope.launch {
            try { runAll(app) } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.w("provision", "runAll", e)
                status.value = "Ошибка: ${e.message ?: e.javaClass.simpleName}"
            } finally { running.value = false }
        }
    }

    private suspend fun runAll(c: Context) {
        status.value = "Получаем список приложений…"
        val items: List<ProvItem> = try {
            val txt = fetchManifest()
            val parsed = parse(txt)
            Prefs.manifest = txt
            parsed
        } catch (e: Exception) {
            val old = cached()
            if (old.isEmpty()) { status.value = e.message ?: "Не удалось получить список приложений"; rows.value = emptyList(); return }
            status.value = "Нет связи с сервером — используем сохранённый список"
            old
        }
        Prefs.protectedPkgs = items.map { it.pkg }.toSet() + Protection.TAPK_PKG
        val list = (listOf(tapkItem) + items).distinctBy { it.id }.map { ProvRow(it) }
        rows.value = list
        for (r in list) {
            try { process(c, r) } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.w("provision", "item ${r.item.id}", e)
                r.state = PState.Error
                r.note = e.message ?: e.javaClass.simpleName
            }
        }
        val ok = list.all { it.state == PState.Done || it.state == PState.Skipped }
        Prefs.skippedIds = list.filter { it.state == PState.Skipped }.map { it.item.id }.toSet()
        if (ok) Prefs.provisionDone = true
        Protection.enforce(c)
        status.value = if (ok) "Все системные приложения на месте" else "Часть приложений не установлена — нажмите «Повторить»"
    }

    private suspend fun process(c: Context, r: ProvRow) {
        val p = r.item
        r.progress = 0
        if (Protection.isInstalled(c, p.pkg)) { r.state = PState.Done; r.note = "Уже установлено"; return }
        if (p.minSdk > Build.VERSION.SDK_INT) { skip(r, "Нужен Android API ${p.minSdk}+, у ТВ ${Build.VERSION.SDK_INT}"); return }
        if (p.abis.isNotEmpty() && !Util.abiCompatible(p.abis.toSet(), Build.SUPPORTED_ABIS)) {
            skip(r, "Только для ${p.abis.joinToString()}, ТВ: ${Build.SUPPORTED_ABIS.joinToString()}"); return
        }
        val file: File = if (p.id == "tapk") copyTapk(c) else { r.state = PState.Download; download(c, p, r) }
        r.state = PState.Install
        r.note = "Подтвердите установку в окне Android"
        val info = ApkInstaller.inspect(c, file, "${p.id}.${p.ext}")
        if (!info.sdkOk || !info.abiOk) { skip(r, "Пакет не подходит этому устройству"); file.delete(); return }
        if (info.pkg != p.pkg) throw IOException("Пакет в файле (${info.pkg}) не совпадает с ожидаемым")
        val sid = ApkInstaller.install(c, info)
        val res = withTimeoutOrNull(15 * 60_000L) { InstallEvents.results.first { m -> m.containsKey(sid) }[sid] }
        when {
            res == null -> { r.state = PState.Error; r.note = "Не дождались подтверждения установки" }
            res.ok -> { r.state = PState.Done; r.note = "Установлено"; file.delete() }
            else -> { r.state = PState.Error; r.note = res.msg }
        }
    }

    private fun skip(r: ProvRow, why: String) { r.state = PState.Skipped; r.note = why }

    private fun copyTapk(c: Context): File {
        val f = File(c.cacheDir, "tapk.apk")
        c.assets.open("tapk.apk").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return f
    }

    private fun download(c: Context, p: ProvItem, r: ProvRow): File {
        val dir = File(c.filesDir, "provision").apply { mkdirs() }
        val fin = File(dir, "${p.id}.${p.ext}")
        if (fin.exists()) { if (fin.length() == p.size && Util.sha256File(fin) == p.sha) return fin else fin.delete() }
        val part = File(dir, "${p.id}.part")
        val need = p.size * 3
        if (StatFs(dir.path).availableBytes < need) throw IOException("Мало места: нужно около ${Util.sizeText(need)}")
        var have = if (part.exists()) part.length() else 0L
        if (have > p.size) { part.delete(); have = 0L }
        val con = http("/api/tv/get.php?id=" + URLEncoder.encode(p.id, "UTF-8"), token())
        if (have > 0) con.setRequestProperty("Range", "bytes=$have-")
        val code = con.responseCode
        if (code == 401) { con.disconnect(); throw IOException("Сессия устарела — войдите по QR заново") }
        if (code != 200 && code != 206) { con.disconnect(); if (code == 416) part.delete(); throw IOException("Сервер не отдал файл (код $code)") }
        val append = code == 206 && have > 0
        if (!append) have = 0L
        var done = have
        var lastPct = -1
        con.inputStream.use { ins ->
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val pct = (done * 100 / p.size).toInt().coerceIn(0, 100)
                    if (pct != lastPct) { lastPct = pct; r.progress = pct; r.note = "${Util.sizeText(done)} из ${Util.sizeText(p.size)}" }
                }
            }
        }
        con.disconnect()
        r.state = PState.Verify
        r.note = "Проверка контрольной суммы"
        if (part.length() != p.size || Util.sha256File(part) != p.sha) {
            part.delete()
            throw IOException("Файл повреждён при загрузке — нажмите «Повторить»")
        }
        if (!part.renameTo(fin)) throw IOException("Не удалось сохранить файл")
        return fin
    }
}
