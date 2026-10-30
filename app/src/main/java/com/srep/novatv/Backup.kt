package com.srep.novatv

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Копия настроек Nova на накопителе ТВ (NovaTV/settings.json).
 * Обычное обновление приложения и так ничего не теряет (данные лежат в приватной памяти).
 * Эта копия нужна на случай, если Nova пришлось удалить и поставить заново: настройки, фон, избранное,
 * «первая настройка» и список системных приложений вернутся сами.
 * Сессия (вход по QR) в копию НЕ попадает — токены нельзя хранить открыто; после переустановки достаточно снова отсканировать QR.
 */
object SettingsBackup {
    private const val SKIP = "session"
    private var sp: SharedPreferences? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    // Слушатель хранится в поле — иначе сборщик мусора его удалит.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != SKIP) schedule()
    }

    private fun file(): File = File(Environment.getExternalStorageDirectory(), "NovaTV/settings.json")

    /** Вызывать сразу после создания SharedPreferences: при пустых настройках пытается восстановить копию. */
    fun attach(c: Context, prefs: SharedPreferences) {
        sp = prefs
        try {
            if (prefs.all.isEmpty()) restore(prefs)
        } catch (e: Throwable) { AppLog.w("backup", "restore", e) }
        try {
            val t = HandlerThread("nova-backup").apply { start() }
            thread = t
            handler = Handler(t.looper)
            prefs.registerOnSharedPreferenceChangeListener(listener)
        } catch (e: Throwable) { AppLog.w("backup", "attach", e) }
    }

    private fun schedule() {
        val h = handler ?: return
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ save() }, 1500)
    }

    @Synchronized
    private fun save() {
        val p = sp ?: return
        try {
            val arr = JSONArray()
            for ((k, v) in p.all) {
                if (k == SKIP || v == null) continue
                val o = JSONObject().put("k", k)
                when (v) {
                    is Boolean -> o.put("t", "b").put("v", v)
                    is Int -> o.put("t", "i").put("v", v)
                    is Long -> o.put("t", "l").put("v", v)
                    is Float -> o.put("t", "f").put("v", v.toDouble())
                    is String -> o.put("t", "s").put("v", v)
                    else -> continue
                }
                arr.put(o)
            }
            val f = file()
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "settings.json.tmp")
            tmp.writeText(JSONObject().put("app", "nova-tv").put("items", arr).toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Throwable) {
            // Нет доступа к накопителю (не выдано разрешение на файлы) — это не ошибка, просто копии не будет.
        }
    }

    private fun restore(p: SharedPreferences) {
        val f = file()
        if (!f.isFile) return
        val arr = JSONObject(f.readText()).getJSONArray("items")
        val e = p.edit()
        for (n in 0 until arr.length()) {
            val o = arr.getJSONObject(n)
            val k = o.getString("k")
            if (k == SKIP) continue
            when (o.getString("t")) {
                "b" -> e.putBoolean(k, o.getBoolean("v"))
                "i" -> e.putInt(k, o.getInt("v"))
                "l" -> e.putLong(k, o.getLong("v"))
                "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                "s" -> e.putString(k, o.getString("v"))
            }
        }
        e.commit()
        AppLog.w("backup", "настройки восстановлены из копии")
    }
}
