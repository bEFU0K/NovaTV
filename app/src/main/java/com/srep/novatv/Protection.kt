package com.srep.novatv

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

class AdminReceiver : DeviceAdminReceiver()

/**
 * Защита системных приложений Nova от удаления.
 *
 * Уровни:
 *  1) В самой Nova у таких приложений нет кнопки «Удалить», а файловый менеджер не удаляет служебные файлы.
 *  2) Если Nova назначена владельцем устройства (одна команда adb, см. инструкцию), Android сам
 *     блокирует удаление этих пакетов — даже из системных «Настроек». Это самая надёжная защита без root.
 *  3) Если приложение всё же удалено (например, сброс), Nova сама предложит скачать и поставить его заново.
 */
object Protection {
    const val TAPK_PKG = "ru.trashbox.tapk"
    const val ADB_CMD = "adb shell dpm set-device-owner com.srep.novatv/.AdminReceiver"

    fun isProtected(pkg: String) = pkg == TAPK_PKG || pkg in Prefs.protectedPkgs

    private fun dpm(c: Context): DevicePolicyManager? = try { c.getSystemService(DevicePolicyManager::class.java) } catch (e: Exception) { null }

    fun isOwner(c: Context): Boolean = try { dpm(c)?.isDeviceOwnerApp(c.packageName) == true } catch (e: Exception) { false }

    fun isInstalled(c: Context, pkg: String): Boolean = try { c.packageManager.getPackageInfo(pkg, 0); true } catch (e: PackageManager.NameNotFoundException) { false } catch (e: Exception) { false }

    /** Блокирует удаление (работает только когда Nova — владелец устройства). Безопасно вызывать сколько угодно раз. */
    fun enforce(c: Context) {
        if (!isOwner(c)) return
        val d = dpm(c) ?: return
        val admin = ComponentName(c, AdminReceiver::class.java)
        for (p in Prefs.protectedPkgs + TAPK_PKG + c.packageName) {
            try { d.setUninstallBlocked(admin, p, true) } catch (e: Exception) { AppLog.w("protect", "block $p", e) }
        }
    }
}
