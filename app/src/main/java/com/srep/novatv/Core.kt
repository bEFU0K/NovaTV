package com.srep.novatv

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Журнал ошибок для диагностики (файл в приватном хранилище, ротация по размеру). */
object AppLog {
    private var f: File? = null

    fun init(c: Context) {
        f = File(c.filesDir, "nova.log")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            w("CRASH", "thread ${t.name}", e)
            prev?.uncaughtException(t, e)
        }
    }

    @Synchronized
    fun w(tag: String, msg: String, e: Throwable? = null) {
        val file = f ?: return
        try {
            if (file.length() > 256_000) file.writeText(file.readText().takeLast(100_000))
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val trace = if (e != null) "\n" + android.util.Log.getStackTraceString(e) else ""
            file.appendText("$ts [$tag] $msg$trace\n")
        } catch (_: Exception) {
        }
    }

    fun read(): String = try { f?.readText() ?: "" } catch (_: Exception) { "" }
    fun clear() { try { f?.writeText("") } catch (_: Exception) { } }
}

/** Настройки лаунчера. Токены здесь хранятся только в зашифрованном виде (см. SecureStore). */
object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { sp = c.getSharedPreferences("nova", Context.MODE_PRIVATE) }

    var setupDone: Boolean
        get() = sp.getBoolean("setup", false)
        set(v) { sp.edit().putBoolean("setup", v).apply() }
    var accent: Int
        get() = sp.getInt("accent", 0)
        set(v) { sp.edit().putInt("accent", v).apply() }
    var wallpaper: Int
        get() = sp.getInt("wall", 0)
        set(v) { sp.edit().putInt("wall", v).apply() }
    var cardSize: Int
        get() = sp.getInt("card", 1)
        set(v) { sp.edit().putInt("card", v).apply() }
    var animate: Boolean
        get() = sp.getBoolean("anim", false)
        set(v) { sp.edit().putBoolean("anim", v).apply() }
    var session: String?
        get() = sp.getString("session", null)
        set(v) {
            val e = sp.edit()
            if (v == null) e.remove("session") else e.putString("session", v)
            e.apply()
        }
    var favorites: List<String>
        get() = list("fav")
        set(v) { sp.edit().putString("fav", v.joinToString(",")).apply() }
    var recents: List<String>
        get() = list("rec")
        set(v) { sp.edit().putString("rec", v.joinToString(",")).apply() }

    var provisionDone: Boolean
        get() = sp.getBoolean("prov", false)
        set(v) { sp.edit().putBoolean("prov", v).apply() }
    /** Копия последнего списка приложений с сервера (чтобы восстановить без сети до списка). */
    var manifest: String?
        get() = sp.getString("manifest", null)
        set(v) { sp.edit().putString("manifest", v).apply() }
    var protectedPkgs: Set<String>
        get() = list("prot").toSet()
        set(v) { sp.edit().putString("prot", v.joinToString(",")).apply() }
    /** id приложений, которые не подходят этому ТВ (не восстанавливаем их бесконечно). */
    var skippedIds: Set<String>
        get() = list("skip").toSet()
        set(v) { sp.edit().putString("skip", v.joinToString(",")).apply() }

    private fun list(k: String): List<String> =
        (sp.getString(k, "") ?: "").split(",").filter { it.isNotBlank() }

    fun resetUi() {
        sp.edit().remove("accent").remove("wall").remove("card").remove("anim").remove("fav").remove("rec").apply()
    }
}

/** AES-256-GCM, ключ хранится в Android Keystore и не покидает его. */
object SecureStore {
    private const val ALIAS = "nova_session_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return g.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray())
        return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    fun decrypt(blob: String): String? = try {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12))
    } catch (e: Exception) {
        null
    }
}
