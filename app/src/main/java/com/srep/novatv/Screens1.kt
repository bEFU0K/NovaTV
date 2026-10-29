package com.srep.novatv

import android.content.Context
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- 1. Первоначальная настройка ----------
@Composable
fun SetupScreen(net: NetState, done: () -> Unit) {
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(760.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Txt("Nova TV OS", 56, bold = true)
            Txt("Шаг ${step + 1} из 4", 16, TextDim)
            when (step) {
                0 -> Txt("Добро пожаловать! Настроим телевизор за минуту.", 24)
                1 -> {
                    Txt(if (net.online) "Подключено: ${net.type}" else "Нет подключения к интернету", 24, if (net.online) TextMain else Danger)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        FocusButton("Настроить Wi-Fi / Ethernet") { Sys.open(ctx, Settings.ACTION_WIFI_SETTINGS) }
                        FocusButton("Bluetooth") { Sys.open(ctx, Settings.ACTION_BLUETOOTH_SETTINGS) }
                    }
                    Txt("Интернет нужен для входа по QR и скачивания приложений.", 16, TextDim)
                }
                2 -> {
                    Txt("Сделайте Nova основным домашним экраном, чтобы кнопка Home открывала его.", 22)
                    FocusButton("Выбрать домашний экран") { Sys.open(ctx, Settings.ACTION_HOME_SETTINGS) }
                }
                else -> {
                    Txt("Разрешите Nova устанавливать приложения и работать с файлами. Дальше — вход по QR и установка системных приложений.", 22)
                    var inst by remember { mutableStateOf(ApkInstaller.canInstall(ctx)) }
                    LaunchedEffect(Unit) { while (true) { inst = ApkInstaller.canInstall(ctx); delay(1200) } }
                    FocusButton(if (inst) "Установка приложений: разрешена ✓" else "Разрешить установку приложений") {
                        Sys.open(ctx, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, ctx.packageName)
                    }
                    FocusButton("Разрешить доступ к файлам") {
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            try {
                                ctx.startActivity(android.content.Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (e: Exception) { Sys.open(ctx, Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION) }
                        } else Sys.open(ctx, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ctx.packageName)
                    }
                }
            }
            key(step) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (step > 0) FocusButton("Назад") { step-- }
                    FocusButton(if (step < 3) "Далее" else "Готово", primary = true, autoFocus = true) { if (step < 3) step++ else done() }
                }
            }
        }
    }
}

// ---------- 2. Вход Srepportal ----------
private sealed interface LState {
    object Loading : LState
    data class Code(val dc: DeviceCode, val qr: ImageBitmap) : LState
    data class Error(val msg: String) : LState
}

private fun qrBitmap(text: String, size: Int = 480): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

@Composable
fun LoginScreen(session: Session?, net: NetState, onDone: () -> Unit, back: (() -> Unit)?, go: (Screen) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    ScreenFrame("Srepportal", back) {
        if (session != null) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Txt("Вы вошли как", 18, TextDim)
                Txt(session.user.name, 36, bold = true)
                Txt("ID: ${session.user.id}", 16, TextDim)
                FocusButton("Выйти из аккаунта", primary = true) { scope.launch { Portal.logout(session); SessionStore.clear() } }
            }
            return@ScreenFrame
        }
        var attempt by remember { mutableIntStateOf(0) }
        var st by remember { mutableStateOf<LState>(LState.Loading) }
        LaunchedEffect(attempt, net.online) {
            st = LState.Loading
            if (!net.online) { st = LState.Error("Нет подключения к интернету"); return@LaunchedEffect }
            try {
                while (true) {
                    val dc = Portal.start()
                    val qr = withContext(Dispatchers.Default) { qrBitmap(dc.verifyUrl) }
                    val deadline = System.currentTimeMillis() + dc.expiresIn * 1000L
                    st = LState.Code(dc, qr)
                    while (System.currentTimeMillis() < deadline) {
                        delay(dc.interval * 1000L)
                        when (val r = Portal.poll(dc)) {
                            is PollResult.Ok -> { SessionStore.save(r.s); onDone(); return@LaunchedEffect }
                            PollResult.Expired -> break
                            PollResult.Denied -> { st = LState.Error("Вход отклонён на телефоне"); return@LaunchedEffect }
                            is PollResult.Fail -> { st = LState.Error(r.msg); return@LaunchedEffect }
                            PollResult.Pending -> {}
                        }
                    }
                    // код истёк — молча выпускаем новый QR
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PortalException) {
                st = LState.Error(e.message ?: "Ошибка")
            } catch (e: Exception) {
                AppLog.w("login", "flow", e)
                st = LState.Error("Некорректный ответ сервера")
            }
        }
        when (val s = st) {
            LState.Loading -> StatusPane("Связываемся с Srepportal…", loading = true)
            is LState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                StatusPane(s.msg, error = true, retry = { attempt++ })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FocusButton("Настроить интернет") { go(Screen.Network) }
                    FocusButton("Bluetooth") { go(Screen.Bluetooth) }
                }
            }
            is LState.Code -> Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(s.qr, null, Modifier.size(320.dp).background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(18.dp)).padding(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Txt("Вход по QR-коду", 34, bold = true)
                    Txt("1. Откройте камеру или Srepportal на телефоне.", 20)
                    Txt("2. Наведите на QR-код.", 20)
                    Txt("3. Войдите в аккаунт (если нужно) и нажмите «Подтвердить вход».", 20)
                    Txt("Код обновляется сам. Других способов входа на этом ТВ нет.", 15, TextDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FocusButton("Новый код", autoFocus = true) { attempt++ }
                        FocusButton("Интернет") { go(Screen.Network) }
                        FocusButton("Bluetooth") { go(Screen.Bluetooth) }
                    }
                }
            }
        }
    }
}

// ---------- 3. Главный экран ----------
@Composable
fun Clock() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    val d = Date(now)
    Column {
        Txt(SimpleDateFormat("HH:mm", Locale.getDefault()).format(d), 56, bold = true)
        Txt(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(d), 18, TextDim)
    }
}

@Composable
fun AppCard(a: AppItem, w: Dp, onKey: ((KeyEvent) -> Boolean)? = null, onClick: () -> Unit) {
    FocusCard(Modifier.width(w), onKey = onKey, onClick = onClick) { f ->
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(a.icon, null, Modifier.size(w * 0.55f))
            Spacer(Modifier.height(8.dp))
            Txt(a.label, 16, if (f) TextMain else TextDim, maxLines = 1)
        }
    }
}

@Composable
fun Tile(title: String, sub: String, onClick: () -> Unit) {
    FocusCard(Modifier.width(250.dp), onClick = onClick) {
        Column(Modifier.padding(18.dp)) {
            Txt(title, 22, bold = true)
            Spacer(Modifier.height(4.dp))
            Txt(sub, 14, TextDim)
        }
    }
}

fun launchApp(ctx: Context, ui: UiState, a: AppItem) { if (AppRepo.launch(ctx, a)) ui.pushRecent(a.pkg) }

// ---------- 4. Приложения ----------
@Composable
fun AppsScreen(ui: UiState, apps: List<AppItem>?, goApk: () -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    var sel by remember { mutableStateOf<AppItem?>(null) }
    ScreenFrame("Все приложения", back) {
        if (apps == null) StatusPane("Загрузка…", loading = true)
        else if (apps.isEmpty()) StatusPane("Приложения не найдены")
        else LazyVerticalGrid(GridCells.Adaptive(ui.cardW + 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(8.dp)) {
            items(apps, key = { it.pkg }) { a -> AppCard(a, ui.cardW) { sel = a } }
        }
    }
    sel?.let { a ->
        Dialog(onDismissRequest = { sel = null }) {
            Column(Modifier.background(Graphite, RoundedCornerShape(20.dp)).padding(28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(a.label, 28, bold = true)
                AppRepo.info(ctx, a.pkg).forEach { Txt(it, 15, TextDim) }
                Spacer(Modifier.height(6.dp))
                FocusButton("Запустить", Modifier.fillMaxWidth(), autoFocus = true, primary = true) { launchApp(ctx, ui, a); sel = null }
                FocusButton(if (a.pkg in ui.favorites) "Убрать из избранного" else "В избранное", Modifier.fillMaxWidth()) { ui.toggleFav(a.pkg); sel = null }
                FocusButton("Сведения / остановить", Modifier.fillMaxWidth()) { AppRepo.details(ctx, a.pkg); sel = null }
                FocusButton("Обновить из APK", Modifier.fillMaxWidth()) { sel = null; goApk() }
                if (Protection.isProtected(a.pkg)) Txt("🔒 Системное приложение Nova — удалить нельзя", 15, TextDim)
                else FocusButton("Удалить", Modifier.fillMaxWidth()) { AppRepo.uninstall(ctx, a.pkg); sel = null }
            }
        }
    }
}
