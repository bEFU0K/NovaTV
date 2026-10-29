package com.srep.novatv

import java.security.MessageDigest

/** Чистые функции без Android-зависимостей (покрыты unit-тестами). */
object Util {
    fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun abiCompatible(libAbis: Set<String>, supported: Array<String>): Boolean =
        libAbis.isEmpty() || libAbis.any { it in supported }

    enum class FileKind { Apk, Bundle, Other }

    fun fileKind(name: String): FileKind {
        val n = name.lowercase()
        return when {
            n.endsWith(".apk") -> FileKind.Apk
            n.endsWith(".xapk") || n.endsWith(".apks") || n.endsWith(".apkm") -> FileKind.Bundle
            else -> FileKind.Other
        }
    }

    fun sha256File(f: java.io.File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val b = ByteArray(1 shl 16)
            while (true) {
                val n = i.read(b)
                if (n < 0) break
                md.update(b, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** true, если child совпадает с parent или лежит внутри него. */
    fun isUnder(child: String, parent: String): Boolean =
        child == parent || child.startsWith(parent.trimEnd('/') + "/")

    fun sizeText(n: Long): String = when {
        n >= 1L shl 30 -> "%.1f ГБ".format(n / (1024.0 * 1024 * 1024))
        n >= 1L shl 20 -> "%.0f МБ".format(n / (1024.0 * 1024))
        n >= 1L shl 10 -> "%.0f КБ".format(n / 1024.0)
        else -> "$n Б"
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    private val RISKY = mapOf(
        "android.permission.BIND_ACCESSIBILITY_SERVICE" to "Служба доступности (может читать экран и нажимать кнопки)",
        "android.permission.BIND_DEVICE_ADMIN" to "Администратор устройства",
        "android.permission.SYSTEM_ALERT_WINDOW" to "Показ поверх других приложений",
        "android.permission.REQUEST_INSTALL_PACKAGES" to "Установка других приложений",
        "android.permission.MANAGE_EXTERNAL_STORAGE" to "Полный доступ ко всем файлам",
        "android.permission.READ_SMS" to "Чтение SMS",
        "android.permission.SEND_SMS" to "Отправка SMS",
        "android.permission.RECEIVE_SMS" to "Перехват SMS",
        "android.permission.READ_CALL_LOG" to "Журнал звонков",
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" to "Чтение всех уведомлений"
    )

    fun risky(perms: List<String>): List<String> = perms.mapNotNull { RISKY[it] }
}
