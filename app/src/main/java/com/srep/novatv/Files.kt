package com.srep.novatv

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private data class FsRoot(val label: String, val dir: File)

private fun fsRoots(): List<FsRoot> {
    val l = ArrayList<FsRoot>()
    try { l.add(FsRoot("Внутренняя память", Environment.getExternalStorageDirectory())) } catch (_: Exception) { }
    try { l.add(FsRoot("Загрузки", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))) } catch (_: Exception) { }
    try {
        File("/storage").listFiles()
            ?.filter { it.name != "self" && it.name != "emulated" && it.canRead() }
            ?.forEach { l.add(FsRoot("Накопитель ${it.name}", it)) }
    } catch (_: Exception) { }
    return l
}

private fun hasAllFiles(c: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(c, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

private fun openWith(c: Context, f: File, pkg: String? = null): Boolean = try {
    val uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    if (pkg != null) i.setPackage(pkg)
    c.startActivity(i)
    true
} catch (e: Exception) {
    AppLog.w("files", "open ${f.name}", e)
    false
}

private fun requestAllFiles(c: Context, legacy: () -> Unit) {
    if (Build.VERSION.SDK_INT >= 30) {
        try {
            c.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Sys.open(c, Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    } else legacy()
}

@Composable
fun FilesScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var allowed by remember { mutableStateOf(hasAllFiles(ctx)) }
    var dir by remember { mutableStateOf<File?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<File>?>(null) }
    var sel by remember { mutableStateOf<File?>(null) }
    var rename by remember { mutableStateOf<File?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf<File?>(null) }
    var apk by remember { mutableStateOf<ApkInfo?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val roots = remember(allowed) { fsRoots() }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { allowed = hasAllFiles(ctx) }
    val canInstall = remember { mutableStateOf(ApkInstaller.canInstall(ctx)) }
    LaunchedEffect(Unit) { while (true) { allowed = hasAllFiles(ctx); canInstall.value = ApkInstaller.canInstall(ctx); delay(1500) } }

    fun up(d: File): File? = if (roots.any { it.dir.absolutePath == d.absolutePath }) null else d.parentFile
    val goBack: () -> Unit = { val d = dir; if (d != null) dir = up(d) else back() }
    BackHandler(enabled = dir != null) { goBack() }

    LaunchedEffect(dir, tick, allowed) {
        val d = dir
        entries = null
        if (d != null) entries = withContext(Dispatchers.IO) {
            d.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        }
    }

    ScreenFrame("Файлы", goBack) {
        if (!allowed) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("Нужен доступ ко всем файлам, иначе содержимое памяти не видно.", 17, Danger, modifier = Modifier.weight(1f))
                FocusButton("Разрешить", primary = true) {
                    requestAllFiles(ctx) { perm.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)) }
                }
            }
        }
        msg?.let { Txt(it, 17, TextMain, modifier = Modifier.padding(bottom = 8.dp)) }
        val d = dir
        if (d == null) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(roots, key = { it.dir.absolutePath }) { r ->
                    FocusCard(Modifier.fillMaxWidth(), autoFocus = r == roots.firstOrNull(), onClick = { dir = r.dir; msg = null }) {
                        Column(Modifier.padding(18.dp)) {
                            Txt("📁  ${r.label}", 22, bold = true)
                            Txt(r.dir.absolutePath, 13, TextDim, maxLines = 1)
                        }
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                FocusButton("Новая папка") { newFolder = true }
                FocusButton("Обновить") { tick++ }
                Txt(d.absolutePath, 14, TextDim, maxLines = 1, modifier = Modifier.weight(1f))
            }
            val list = entries
            if (list == null || busy) StatusPane("Загрузка…", loading = true)
            else if (list.isEmpty()) Txt("Папка пуста (или нет доступа)", 20, TextDim)
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.absolutePath }) { f ->
                    val kind = Util.fileKind(f.name)
                    FocusCard(Modifier.fillMaxWidth(), onClick = { if (f.isDirectory) { dir = f; msg = null } else sel = f }) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val icon = if (f.isDirectory) "📁" else if (kind != Util.FileKind.Other) "📦" else "📄"
                            Txt("$icon  ${f.name}", 19, maxLines = 1, modifier = Modifier.weight(1f))
                            Txt(if (f.isDirectory) "папка" else Formatter.formatFileSize(ctx, f.length()), 14, TextDim)
                        }
                    }
                }
            }
        }
    }

    sel?.let { f ->
        val kind = Util.fileKind(f.name)
        Dialog(onDismissRequest = { sel = null }) {
            Column(Modifier.width(520.dp).background(Graphite, RoundedCornerShape(20.dp)).padding(28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(f.name, 24, bold = true, maxLines = 2)
                Txt("${Formatter.formatFileSize(ctx, f.length())} · ${DateFormat.getDateTimeInstance().format(Date(f.lastModified()))}", 15, TextDim)
                if (kind != Util.FileKind.Other) FocusButton("Установить", Modifier.fillMaxWidth(), autoFocus = true, primary = true) {
                    sel = null
                    scope.launch {
                        busy = true
                        try { apk = withContext(Dispatchers.IO) { ApkInstaller.inspect(ctx, f, f.name) } }
                        catch (e: ApkException) { msg = e.message }
                        catch (e: Exception) { AppLog.w("files", "inspect", e); msg = "Не удалось прочитать файл" }
                        busy = false
                    }
                }
                if (kind == Util.FileKind.Bundle && Protection.isInstalled(ctx, Protection.TAPK_PKG)) FocusButton("Открыть в TAPK", Modifier.fillMaxWidth()) {
                    sel = null
                    if (!openWith(ctx, f, Protection.TAPK_PKG)) {
                        val li = ctx.packageManager.getLaunchIntentForPackage(Protection.TAPK_PKG)
                        if (li != null) {
                            Toast.makeText(ctx, "TAPK открыт — выберите файл ${f.name} в нём", Toast.LENGTH_LONG).show()
                            ctx.startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } else msg = "TAPK не удалось открыть"
                    }
                }
                FocusButton("Открыть другим приложением", Modifier.fillMaxWidth(), autoFocus = kind == Util.FileKind.Other) {
                    sel = null
                    if (!openWith(ctx, f)) msg = "Нет приложения, которое умеет открывать этот файл"
                }
                FocusButton("Переименовать", Modifier.fillMaxWidth()) { rename = f; sel = null }
                FocusButton("Удалить", Modifier.fillMaxWidth()) { confirmDel = f; sel = null }
            }
        }
    }

    confirmDel?.let { f ->
        Dialog(onDismissRequest = { confirmDel = null }) {
            Column(Modifier.width(520.dp).background(Graphite, RoundedCornerShape(20.dp)).padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt("Удалить «${f.name}»?", 24, bold = true, maxLines = 3)
                if (f.isDirectory) Txt("Папка будет удалена вместе со всем содержимым.", 16, Danger)
                FocusButton("Отмена", Modifier.fillMaxWidth(), autoFocus = true, primary = true) { confirmDel = null }
                FocusButton("Удалить", Modifier.fillMaxWidth()) {
                    confirmDel = null
                    val prot = File(ctx.filesDir, "provision").absolutePath
                    if (Util.isUnder(f.absolutePath, prot)) msg = "Это системный файл Nova — удалить нельзя"
                    else scope.launch {
                        val ok = withContext(Dispatchers.IO) { try { if (f.isDirectory) f.deleteRecursively() else f.delete() } catch (e: Exception) { false } }
                        msg = if (ok) "Удалено: ${f.name}" else "Не удалось удалить (нет доступа к файлам?)"
                        tick++
                    }
                }
            }
        }
    }

    rename?.let { f ->
        TextEntryDialog("Новое имя", f.name) { r ->
            rename = null
            if (r != null && r.isNotBlank() && r != f.name) {
                if (r.contains('/')) msg = "В имени нельзя использовать «/»"
                else {
                    val t = File(f.parentFile, r)
                    msg = when {
                        t.exists() -> "Файл с таким именем уже есть"
                        f.renameTo(t) -> "Переименовано"
                        else -> "Не удалось переименовать"
                    }
                    tick++
                }
            }
        }
    }

    if (newFolder) {
        TextEntryDialog("Имя новой папки", "") { r ->
            newFolder = false
            val d = dir
            if (r != null && r.isNotBlank() && d != null) {
                msg = if (r.contains('/')) "В имени нельзя использовать «/»" else if (File(d, r).mkdirs()) "Папка создана" else "Не удалось создать папку"
                tick++
            }
        }
    }

    apk?.let { i ->
        Dialog(onDismissRequest = { apk = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.width(760.dp).heightIn(max = 560.dp).background(Graphite, RoundedCornerShape(20.dp)).verticalScroll(rememberScrollState()).padding(12.dp)) {
                ApkCard(i, canInstall.value, ctx, onError = { msg = it; apk = null }, onInstalled = { apk = null })
            }
        }
    }
}
