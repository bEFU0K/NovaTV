package com.srep.novatv

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class PortalUser(val id: String, val name: String, val avatar: String?)
data class Session(val access: String, val refresh: String?, val expiresAt: Long, val user: PortalUser)
data class DeviceCode(val deviceCode: String, val userCode: String, val verifyUrl: String, val expiresIn: Int, val interval: Int)

sealed class PollResult {
    object Pending : PollResult()
    object Expired : PollResult()
    object Denied : PollResult()
    data class Ok(val s: Session) : PollResult()
    data class Fail(val msg: String) : PollResult()
}

class PortalException(msg: String) : Exception(msg)

/** User-Agent ТВ: сайт блокирует «java/…» и пустые UA как ботов, поэтому свой обязателен. */
val NOVA_UA: String get() = "NovaTV/" + BuildConfig.VERSION_NAME + " (Android TV)"

/**
 * Адаптер интеграции Srepportal.
 *
 * ВАЖНО: документации API Srepportal для ТВ-клиентов нет. Здесь реализован КОНТРАКТ (device-code flow,
 * как «вход по QR»), который должен быть реализован на стороне сайта — см. README, раздел «Контракт Srepportal».
 * Пока сервер не отвечает на /api/tv/*, приложение честно показывает ошибку, ничего не имитируя.
 * Чтобы переключиться на другой протокол — замените реализацию PortalAuth, UI не изменится.
 */
interface PortalAuth {
    suspend fun start(): DeviceCode
    suspend fun poll(code: DeviceCode): PollResult
    /** null — сервер отклонил refresh (нужен новый вход); исключение — сеть недоступна. */
    suspend fun refresh(s: Session): Session?
    suspend fun logout(s: Session)
}

object Portal : PortalAuth {
    private val base get() = BuildConfig.PORTAL_URL.trimEnd('/')

    private suspend fun call(path: String, body: JSONObject, bearer: String? = null): Pair<Int, JSONObject?> =
        withContext(Dispatchers.IO) {
            if (!base.startsWith("https://")) throw PortalException("Адрес Srepportal должен начинаться с https://")
            try {
                val c = URL(base + path).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("User-Agent", NOVA_UA)
                if (bearer != null) { c.setRequestProperty("Authorization", "Bearer $bearer"); c.setRequestProperty("X-Nova-Token", bearer) }
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
                c.disconnect()
                val json = try { if (txt.isNullOrBlank()) null else JSONObject(txt) } catch (e: Exception) { null }
                Pair(code, json)
            } catch (e: IOException) {
                throw PortalException("Нет соединения с Srepportal")
            }
        }

    private fun parse(j: JSONObject, old: Session? = null): Session {
        val u = j.optJSONObject("user")
        val user = if (u != null) PortalUser(u.optString("id"), u.optString("name"), u.optString("avatar").ifBlank { null })
        else old?.user ?: throw PortalException("Сервер не вернул данные аккаунта")
        val access = j.optString("access_token")
        if (access.isBlank()) throw PortalException("Сервер не вернул токен")
        val refresh = j.optString("refresh_token").ifBlank { old?.refresh }
        return Session(access, refresh, System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000, user)
    }

    override suspend fun start(): DeviceCode {
        val (code, j) = call("/api/tv/code.php", JSONObject().put("client", "nova-tv").put("device", Build.MODEL))
        if (code == 404) throw PortalException("Сервер Srepportal ещё не поддерживает вход с ТВ (нет /api/tv/code.php — загрузите серверный zip)")
        if (code !in 200..299 || j == null) throw PortalException("Ошибка сервера ($code)")
        return DeviceCode(
            j.getString("device_code"), j.optString("user_code"), j.getString("verification_url"),
            j.optInt("expires_in", 300), j.optInt("interval", 3).coerceAtLeast(2)
        )
    }

    override suspend fun poll(code: DeviceCode): PollResult {
        val (http, j) = call("/api/tv/poll.php", JSONObject().put("device_code", code.deviceCode))
        if (http == 429) return PollResult.Pending
        if (http !in 200..299 || j == null) return PollResult.Fail("Ошибка сервера ($http)")
        return when (j.optString("status")) {
            "pending" -> PollResult.Pending
            "expired" -> PollResult.Expired
            "denied" -> PollResult.Denied
            "ok" -> PollResult.Ok(parse(j))
            else -> PollResult.Fail("Неизвестный ответ сервера")
        }
    }

    override suspend fun refresh(s: Session): Session? {
        val rt = s.refresh ?: return null
        val (http, j) = call("/api/tv/refresh.php", JSONObject().put("refresh_token", rt))
        if (http == 400 || http == 401) return null
        if (http !in 200..299 || j == null) throw PortalException("Ошибка сервера ($http)")
        return parse(j, s)
    }

    override suspend fun logout(s: Session) {
        try { call("/api/tv/logout.php", JSONObject(), s.access) } catch (e: Exception) { AppLog.w("portal", "logout", e) }
    }
}

object SessionStore {
    val flow = MutableStateFlow<Session?>(null)

    fun load() {
        val blob = Prefs.session ?: return
        val raw = SecureStore.decrypt(blob)
        if (raw == null) { Prefs.session = null; return }
        flow.value = try {
            val j = JSONObject(raw)
            val u = j.getJSONObject("user")
            Session(
                j.getString("access"), j.optString("refresh").ifBlank { null }, j.getLong("exp"),
                PortalUser(u.getString("id"), u.getString("name"), u.optString("avatar").ifBlank { null })
            )
        } catch (e: Exception) { null }
    }

    fun save(s: Session) {
        val j = JSONObject().put("access", s.access).put("refresh", s.refresh ?: "").put("exp", s.expiresAt)
            .put("user", JSONObject().put("id", s.user.id).put("name", s.user.name).put("avatar", s.user.avatar ?: ""))
        Prefs.session = SecureStore.encrypt(j.toString())
        flow.value = s
    }

    fun clear() { Prefs.session = null; flow.value = null }
}
