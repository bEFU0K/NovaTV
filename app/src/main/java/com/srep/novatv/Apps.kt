package com.srep.novatv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

data class AppItem(val pkg: String, val label: String, val icon: ImageBitmap, val intent: Intent)

object AppRepo {
    val apps = MutableStateFlow<List<AppItem>?>(null)

    suspend fun reload(c: Context) {
        apps.value = withContext(Dispatchers.IO) { load(c) }
    }

    private fun load(c: Context): List<AppItem> {
        val pm = c.packageManager
        val seen = HashSet<String>()
        val out = ArrayList<AppItem>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val p = ri.activityInfo.packageName
                if (p == c.packageName || !seen.add(p)) continue
                try {
                    val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
                        .setClassName(p, ri.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    out.add(AppItem(p, ri.loadLabel(pm).toString(), ri.loadIcon(pm).toBitmap(128, 128).asImageBitmap(), i))
                } catch (e: Exception) {
                    AppLog.w("apps", "load $p", e)
                }
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    fun launch(c: Context, a: AppItem): Boolean = try {
        c.startActivity(a.intent); true
    } catch (e: Exception) {
        AppLog.w("apps", "launch ${a.pkg}", e)
        Toast.makeText(c, "Не удалось запустить «${a.label}»", Toast.LENGTH_SHORT).show()
        false
    }

    /** «Остановка» в обычном приложении невозможна — открываем системный экран со кнопкой «Остановить». */
    fun details(c: Context, pkg: String) = Sys.open(c, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)

    fun uninstall(c: Context, pkg: String) {
        try {
            c.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.w("apps", "uninstall $pkg", e)
            details(c, pkg)
        }
    }

    fun info(c: Context, pkg: String): List<String> = try {
        val pi = c.packageManager.getPackageInfo(pkg, 0)
        val size = File(pi.applicationInfo?.sourceDir ?: "").length()
        listOf(
            "Пакет: $pkg",
            "Версия: ${pi.versionName ?: "?"} (${PackageInfoCompat.getLongVersionCode(pi)})",
            "Размер APK: ${Formatter.formatFileSize(c, size)}",
            "Установлено: ${DateFormat.getDateInstance().format(Date(pi.firstInstallTime))}"
        )
    } catch (e: Exception) {
        listOf("Пакет: $pkg")
    }
}
