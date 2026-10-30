package com.srep.novatv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------- 5. Центр установки APK ----------
@Composable
fun ApkScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<ApkInfo?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<File>?>(null) }
    var allowed by remember { mutableStateOf(ApkInstaller.canInstall(ctx)) }
    val installMsg by InstallEvents.message.collectAsState()
    LaunchedEffect(Unit) { while (true) { allowed = ApkInstaller.canInstall(ctx); delay(1500) } }

    fun load(block: () -> ApkInfo) {
        scope.launch {
            busy = true; msg = null; InstallEvents.message.value = null
            try {
                info = withContext(Dispatchers.IO) { block() }
            } catch (e: ApkException) {
                info = null; msg = e.message
            } catch (e: Exception) {
                AppLog.w("apk", "inspect", e); info = null; msg = "Не удалось прочитать файл"
            }
            busy = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) load { ApkInstaller.inspectUri(ctx, uri) }
    }

    ScreenFrame("Установка APK", back) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.width(400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusButton("Выбрать файл (USB / хранилище)", Modifier.fillMaxWidth(), primary = true) {
                    try { picker.launch(arrayOf("*/*")) } catch (e: Exception) { msg = "Системный выбор файлов недоступен — используйте поиск на накопителях" }
                }
                FocusButton("Найти APK на накопителях", Modifier.fillMaxWidth()) {
                    scope.launch { busy = true; found = withContext(Dispatchers.IO) { ApkInstaller.scan() }; busy = false }
                }
                FocusButton(if (allowed) "Установка из Nova: разрешена ✓" else "Разрешить установку из Nova", Modifier.fillMaxWidth()) {
                    Sys.open(ctx, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, ctx.packageName)
                }
                FocusButton("Права других установщиков", Modifier.fillMaxWidth()) { Sys.open(ctx, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) }
                Txt("Установка идёт через стандартный установщик Android: система всегда спросит подтверждение. Скрытой установки нет.", 14, TextDim)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (busy) StatusPane("Обработка…", loading = true)
                msg?.let { Txt(it, 20, Danger) }
                installMsg?.let { Txt(it, 20) }
                info?.let { i -> ApkCard(i, allowed, ctx, onError = { msg = it }) }
                found?.let { list ->
                    if (list.isEmpty()) Txt("Файлы не найдены (Android может не давать доступ к USB напрямую — используйте «Выбрать файл»)", 16, TextDim)
                    else {
                        Txt("Найдено: ${list.size}", 18, bold = true)
                        list.forEach { f ->
                            FocusCard(Modifier.fillMaxWidth(), onClick = { load { ApkInstaller.inspect(ctx, f, f.name) } }) {
                                Column(Modifier.padding(14.dp)) {
                                    Txt(f.name, 18, maxLines = 1)
                                    Txt("${f.parent} · ${Formatter.formatFileSize(ctx, f.length())}", 13, TextDim, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                if (info == null && msg == null && found == null && !busy) Txt("Выберите файл .apk, .apks, .xapk или .apkm", 20, TextDim)
            }
        }
    }
}

@Composable
fun ApkCard(i: ApkInfo, allowed: Boolean, ctx: Context, onError: (String) -> Unit, onInstalled: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(16.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(i.label, 28, bold = true)
        Txt(i.pkg, 14, TextDim)
        Txt("Версия: ${i.versionName} (${i.versionCode}) · ${Formatter.formatFileSize(ctx, i.size)}", 17)
        Txt(if (i.installedVersion != null) "Обновление (сейчас установлена ${i.installedVersion})" else "Новая установка", 17)
        Txt("Android: нужен API ${i.minSdk}+ — ${if (i.sdkOk) "совместимо" else "НЕ СОВМЕСТИМО"}", 17, if (i.sdkOk) TextMain else Danger)
        Txt(i.abiText + if (i.abiOk) "" else " — НЕ СОВМЕСТИМО", 15, if (i.abiOk) TextDim else Danger)
        if (i.risky.isNotEmpty()) {
            Txt("⚠ Потенциально опасные разрешения:", 17, Danger, bold = true)
            i.risky.forEach { Txt("• $it", 15, Danger) }
        }
        Txt("Запрашиваемые разрешения (${i.permissions.size}):", 16, bold = true)
        i.permissions.take(12).forEach { Txt(it.removePrefix("android.permission."), 13, TextDim) }
        if (i.permissions.size > 12) Txt("…и ещё ${i.permissions.size - 12}", 13, TextDim)
        Spacer(Modifier.height(6.dp))
        if (!i.sdkOk || !i.abiOk) Txt("Установка заблокирована: приложение не подходит этому устройству.", 17, Danger)
        else if (!allowed) Txt("Сначала разрешите установку из Nova (кнопка слева).", 17, Danger)
        else FocusButton(if (i.risky.isEmpty()) "Установить" else "Всё равно установить", primary = true) {
            try { ApkInstaller.install(ctx, i); onInstalled() } catch (e: Exception) {
                AppLog.w("apk", "install", e); onError("Не удалось начать установку: ${e.message}")
            }
        }
    }
}

// ---------- 6. VPN ----------
@Composable
fun VpnScreen(vpn: Boolean, net: NetState, goApk: () -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val since by VpnMonitor.since.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(vpn) { while (vpn) { now = System.currentTimeMillis(); delay(1000) } }
    val vpnApps = remember {
        val pm = ctx.packageManager
        pm.queryIntentServices(Intent("android.net.VpnService"), 0)
            .map { it.serviceInfo.packageName }.filter { it != ctx.packageName }.distinct()
            .map { it to (try { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() } catch (e: Exception) { it }) }
    }
    ScreenFrame("VPN", back) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(16.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(if (vpn) "VPN подключён" else "VPN не подключён", 32, if (vpn) Accents[2] else TextMain, bold = true)
                if (vpn && since > 0) Txt("Время подключения: ${Util.duration(now - since)}", 20)
                Txt("Сеть: ${if (net.online) net.type else "нет интернета"}", 16, TextDim)
                Txt("Протокол, сервер и IP показывает сам VPN-клиент — системный API их не сообщает.", 14, TextDim)
            }
            Txt("Установленные VPN-приложения", 22, bold = true)
            if (vpnApps.isEmpty()) Txt("Не найдено. Установите OpenVPN, WireGuard или другой клиент из APK.", 16, TextDim)
            vpnApps.forEach { (pkg, label) ->
                FocusCard(Modifier.fillMaxWidth(), onClick = {
                    val li = ctx.packageManager.getLaunchIntentForPackage(pkg)
                    if (li != null) ctx.startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else AppRepo.details(ctx, pkg)
                }) { Txt("$label — открыть и подключить", 20, modifier = Modifier.padding(16.dp)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusButton("Always-on и блокировка без VPN") { Sys.open(ctx, Settings.ACTION_VPN_SETTINGS) }
                FocusButton("Установить VPN из APK") { goApk() }
                FocusButton("Проверить снова", primary = true) { VpnMonitor.recheck() }
            }
            Txt("Утечки при обрыве предотвращает системная опция «Блокировать подключения без VPN» (кнопка выше). Если подключение не удалось, откройте клиент и повторите — причину ошибки показывает он.", 14, TextDim)
        }
    }
}

// ---------- 7. Настройки ----------
@Composable
fun SettingsScreen(ui: UiState, session: Session?, go: (Screen) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasBt = ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH)
    val items = buildList<Pair<String, () -> Unit>> {
        add("Интернет: Wi-Fi / Ethernet" to { go(Screen.Network) })
        if (hasBt) add("Bluetooth" to { go(Screen.Bluetooth) })
        add("Файлы" to { go(Screen.Files) })
        add("Установка APK" to { go(Screen.Apk) })
        add("Системные приложения" to { go(Screen.Provision) })
        add("Изображение" to { Sys.open(ctx, Settings.ACTION_DISPLAY_SETTINGS); Unit })
        add("Дата и время" to { Sys.open(ctx, Settings.ACTION_DATE_SETTINGS); Unit })
        add("Язык" to { Sys.open(ctx, Settings.ACTION_LOCALE_SETTINGS); Unit })
        add("Все приложения Android (системный список)" to { Sys.open(ctx, Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS); Unit })
        add("Хранилище" to { Sys.open(ctx, Settings.ACTION_INTERNAL_STORAGE_SETTINGS); Unit })
        add("Перезагрузка и восстановление (система)" to { Sys.open(ctx, Settings.ACTION_PRIVACY_SETTINGS); Unit })
        add("Обновление Nova" to { go(Screen.Update) })
        add("Об устройстве" to { go(Screen.About) })
        add("Журнал и диагностика" to { go(Screen.Diagnostics) })
        add("Фон: ${WallpaperNames[ui.wallpaper]}" to { ui.cycleWallpaper() })
        add("Акцент: ${AccentNames[ui.accent]}" to { ui.cycleAccent() })
        add("Карточки: ${CardSizeNames[ui.cardSize]}" to { ui.cycleCards() })
        add("Анимация фона: ${if (ui.animate) "вкл" else "выкл"}" to { ui.toggleAnim() })
        add("Сбросить настройки Nova" to { ui.reset() })
        if (session != null) add("Выйти из Srepportal" to { scope.launch { Portal.logout(session); SessionStore.clear() }; Unit })
    }
    ScreenFrame("Настройки", back) {
        LazyVerticalGrid(GridCells.Adaptive(330.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(8.dp)) {
            items(items) { (label, act) -> FocusButton(label, Modifier.fillMaxWidth(), onClick = act) }
        }
    }
}

// ---------- 8. Об устройстве ----------
@Composable
fun AboutScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val lines = remember { DeviceInfo.lines(ctx) }
    ScreenFrame("Об устройстве", back) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            lines.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(12.dp)).padding(16.dp)) {
                    Txt(k, 18, TextDim, modifier = Modifier.width(260.dp))
                    Txt(v, 18, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ---------- 9. Обновление ----------
@Composable
fun UpdateScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Установлена версия ${BuildConfig.VERSION_NAME}") }
    var err by remember { mutableStateOf(false) }
    var upd by remember { mutableStateOf<UpdateInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    val installMsg by InstallEvents.message.collectAsState()
    ScreenFrame("Обновление Nova TV OS", back) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Txt(status, 22, if (err) Danger else TextMain)
            installMsg?.let { Txt(it, 18) }
            if (busy) StatusPane(if (progress > 0) "Загрузка: $progress%" else "Подождите…", loading = true)
            FocusButton("Проверить обновления", primary = true) {
                if (BuildConfig.UPDATE_URL.isBlank()) {
                    err = true; status = "Адрес обновлений не задан. Соберите с -PupdateUrl=https://…/update.json"
                } else scope.launch {
                    busy = true; err = false; upd = null
                    try {
                        val u = Updater.check(BuildConfig.UPDATE_URL)
                        upd = u
                        status = if (u == null) "Установлена последняя версия (${BuildConfig.VERSION_NAME})" else "Доступна версия ${u.name}. ${u.notes}"
                    } catch (e: Exception) {
                        AppLog.w("update", "check", e); err = true; status = "Не удалось проверить обновления: нет связи или неверный ответ сервера"
                    }
                    busy = false
                }
            }
            upd?.let { u ->
                FocusButton("Скачать и установить ${u.name}") {
                    scope.launch {
                        busy = true; err = false; progress = 0
                        try {
                            val f = Updater.download(ctx, u) { progress = it }
                            val info = withContext(Dispatchers.IO) { ApkInstaller.inspect(ctx, f, f.name) }
                            if (info.pkg != ctx.packageName) throw ApkException("Пакет обновления не принадлежит Nova — отклонено")
                            ApkInstaller.install(ctx, info)
                            status = "Подтвердите установку в системном окне"
                        } catch (e: Exception) {
                            AppLog.w("update", "install", e); err = true; status = e.message ?: "Ошибка обновления"
                        }
                        busy = false
                    }
                }
            }
            Txt("Обновляется только лаунчер. Прошивка (системный образ) обновляется штатными средствами устройства: OTA или recovery.", 14, TextDim)
        }
    }
}

// ---------- 10. Диагностика, ошибки, восстановление связи ----------
@Composable
fun DiagnosticsScreen(net: NetState, vpn: Boolean, back: () -> Unit) {
    val ctx = LocalContext.current
    var log by remember { mutableStateOf(AppLog.read().takeLast(6000)) }
    ScreenFrame("Журнал и диагностика", back) {
        if (!net.online) StatusPane("Нет подключения к интернету", error = true, retryLabel = "Открыть настройки сети") { Sys.open(ctx, Settings.ACTION_WIFI_SETTINGS) }
        else Txt("Сеть: ${net.type} · VPN: ${if (vpn) "вкл" else "выкл"}", 20)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 12.dp)) {
            FocusButton("Обновить журнал") { log = AppLog.read().takeLast(6000) }
            FocusButton("Очистить журнал") { AppLog.clear(); log = "" }
        }
        Column(Modifier.fillMaxWidth().weight(1f).background(Surface, RoundedCornerShape(12.dp)).padding(16.dp).verticalScroll(rememberScrollState())) {
            Txt(if (log.isBlank()) "Журнал пуст — ошибок не было." else log, 13, TextDim)
        }
    }
}
