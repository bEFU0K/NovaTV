package com.srep.novatv

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// ---------- Сеть ----------
object NetInfo {
    fun ip(c: Context): String = try {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork)
        lp?.linkAddresses?.map { it.address }?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: "—"
    } catch (e: Exception) { "—" }

    suspend fun checkPortal(): String = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        try {
            val c = URL(BuildConfig.PORTAL_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.requestMethod = "HEAD"
            c.setRequestProperty("User-Agent", NOVA_UA)
            val code = c.responseCode
            c.disconnect()
            "Srepportal отвечает: HTTP $code, ${(System.nanoTime() - t0) / 1_000_000} мс"
        } catch (e: Exception) {
            "Нет связи с Srepportal (${e.javaClass.simpleName})"
        }
    }
}

@Composable
fun NetworkScreen(net: NetState, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ip by remember { mutableStateOf("—") }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(net) { ip = NetInfo.ip(ctx) }
    ScreenFrame("Интернет", back) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(16.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(if (net.online) "Подключено: ${net.type}" else "Нет подключения к интернету", 30, if (net.online) TextMain else Danger, bold = true)
                Txt("IP-адрес: $ip", 18, TextDim)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusButton("Wi-Fi: выбрать сеть и ввести пароль", primary = true, autoFocus = true) { Sys.open(ctx, Settings.ACTION_WIFI_SETTINGS) }
                FocusButton("Другие сети") { Sys.open(ctx, Settings.ACTION_WIRELESS_SETTINGS) }
            }
            FocusButton(if (busy) "Проверяем…" else "Проверить интернет и Srepportal") {
                if (!busy) scope.launch { busy = true; result = NetInfo.checkPortal(); busy = false }
            }
            result?.let { Txt(it, 20, if (it.startsWith("Нет")) Danger else TextMain) }
            Txt("Ethernet подключается сам при вставленном кабеле. Пароль Wi-Fi вводится в системном окне Android: приложение без системных прав не может подключать к сети напрямую.", 14, TextDim)
        }
    }
}

// ---------- Bluetooth ----------
private fun btPerms(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

@Suppress("DEPRECATION")
private fun deviceOf(i: Intent): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

@SuppressLint("MissingPermission")
private fun nameOf(d: BluetoothDevice): String = try { d.name ?: d.address } catch (e: Exception) { d.address }

@Composable
fun BluetoothScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val adapter = remember { try { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter } catch (e: Exception) { null } }
    ScreenFrame("Bluetooth", back) {
        if (adapter == null) StatusPane("На этом устройстве нет Bluetooth", error = true, retryLabel = "Системные настройки") { Sys.open(ctx, Settings.ACTION_BLUETOOTH_SETTINGS) }
        else BluetoothBody(adapter)
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun BluetoothBody(adapter: BluetoothAdapter) {
    val ctx = LocalContext.current
    fun granted() = btPerms().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    var ok by remember { mutableStateOf(granted()) }
    var on by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var bonded by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    val found = remember { mutableStateListOf<BluetoothDevice>() }
    var msg by remember { mutableStateOf<String?>(null) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ok = granted() }

    fun refresh() {
        on = try { adapter.isEnabled } catch (e: Exception) { false }
        bonded = if (ok && on) try { adapter.bondedDevices.toList() } catch (e: Exception) { emptyList() } else emptyList()
    }
    LaunchedEffect(ok) { refresh() }
    DisposableEffect(ok) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d = deviceOf(i)
                        if (d != null && found.none { it.address == d.address }) found.add(d)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
                    BluetoothAdapter.ACTION_STATE_CHANGED, BluetoothDevice.ACTION_BOND_STATE_CHANGED -> refresh()
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            try { ctx.unregisterReceiver(r) } catch (_: Exception) { }
            try { if (ok) adapter.cancelDiscovery() } catch (_: Exception) { }
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
        if (!ok) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Txt("Для поиска и подключения устройств нужен доступ к Bluetooth.", 20)
                    FocusButton("Разрешить доступ", primary = true, autoFocus = true) { perm.launch(btPerms()) }
                }
            }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FocusButton(if (on) "Bluetooth: включён ✓" else "Включить Bluetooth", primary = !on, autoFocus = true) {
                        if (!on) {
                            try { ctx.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            catch (e: Exception) { Sys.open(ctx, Settings.ACTION_BLUETOOTH_SETTINGS) }
                        } else Sys.open(ctx, Settings.ACTION_BLUETOOTH_SETTINGS)
                    }
                    if (on) FocusButton(if (scanning) "Идёт поиск…" else "Искать устройства", primary = true) {
                        if (!scanning) {
                            found.clear()
                            scanning = try { adapter.startDiscovery() } catch (e: Exception) { false }
                            msg = if (scanning) null else "Не удалось начать поиск — попробуйте ещё раз"
                        }
                    }
                    FocusButton("Системные настройки") { Sys.open(ctx, Settings.ACTION_BLUETOOTH_SETTINGS) }
                }
            }
            msg?.let { m -> item { Txt(m, 18, Danger) } }
            if (!on) item { Txt("Bluetooth выключен. Включите его, чтобы подключить пульт, наушники или колонку.", 18, TextDim) }
            if (on) {
                item { Txt("Сопряжённые устройства", 22, bold = true) }
                if (bonded.isEmpty()) item { Txt("Пока нет. Переведите устройство в режим сопряжения и нажмите «Искать устройства».", 16, TextDim) }
                items(bonded, key = { "b" + it.address }) { d ->
                    FocusCard(Modifier.fillMaxWidth(), onClick = { Sys.open(ctx, Settings.ACTION_BLUETOOTH_SETTINGS) }) {
                        Column(Modifier.padding(16.dp)) {
                            Txt(nameOf(d), 20, maxLines = 1)
                            Txt("${d.address} · нажмите, чтобы подключить или забыть в системных настройках", 13, TextDim, maxLines = 1)
                        }
                    }
                }
                item { Txt(if (scanning) "Найденные устройства (поиск идёт…)" else "Найденные устройства", 22, bold = true) }
                val fresh = found.filter { f -> bonded.none { it.address == f.address } }
                if (fresh.isEmpty()) item { Txt(if (scanning) "Ищем…" else "Пусто", 16, TextDim) }
                items(fresh, key = { "f" + it.address }) { d ->
                    FocusCard(Modifier.fillMaxWidth(), onClick = {
                        try { adapter.cancelDiscovery(); scanning = false; msg = if (d.createBond()) null else "Не удалось начать сопряжение" }
                        catch (e: Exception) { AppLog.w("bt", "bond", e); msg = "Нет разрешения на сопряжение" }
                    }) {
                        Column(Modifier.padding(16.dp)) {
                            Txt(nameOf(d), 20, maxLines = 1)
                            Txt("${d.address} · нажмите OK для сопряжения", 13, TextDim, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

// ---------- Системные приложения (установка и защита) ----------
@Composable
fun ProvisionScreen(session: Session?, onDone: () -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val rows by Provision.rows.collectAsState()
    val running by Provision.running.collectAsState()
    val status by Provision.status.collectAsState()
    val owner = remember { Protection.isOwner(ctx) }
    LaunchedEffect(Unit) { if (session != null && !Provision.running.value) Provision.start(ctx.applicationContext) }
    ScreenFrame("Системные приложения", back) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt(if (running) "Идёт установка. Android спросит подтверждение для каждого приложения — нажмите «Установить»." else status.ifBlank { "Готово к установке" },
                18, if (!running && status.startsWith("Ошибка")) Danger else TextMain)
            LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.item.id }) { r ->
                    Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Txt(r.item.name, 19, bold = true, maxLines = 1, modifier = Modifier.weight(1f))
                            Txt(if (r.state == PState.Download) "${r.state.text} ${r.progress}%" else r.state.text, 16,
                                when (r.state) { PState.Error -> Danger; PState.Done -> Accents[2]; else -> TextDim })
                        }
                        if (r.note.isNotBlank()) Txt(r.note, 13, if (r.state == PState.Error) Danger else TextDim, maxLines = 2)
                        if (r.state == PState.Download) {
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.fillMaxWidth().height(6.dp).background(Bg, RoundedCornerShape(3.dp))) {
                                Box(Modifier.fillMaxWidth(r.progress / 100f).fillMaxHeight().background(LocalAccent.current, RoundedCornerShape(3.dp)))
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!running) FocusButton(if (rows.isEmpty()) "Попробовать снова" else "Повторить / доустановить", primary = true, autoFocus = true) { Provision.start(ctx.applicationContext) }
                FocusButton(if (Prefs.provisionDone) "Готово" else "Пропустить сейчас") { onDone() }
            }
            Txt(
                if (owner) "Защита от удаления включена на уровне Android: эти приложения нельзя удалить даже из системных настроек."
                else "Эти приложения скрыты от удаления в Nova и восстанавливаются автоматически. Полная блокировка на уровне Android включается одной командой: ${Protection.ADB_CMD}",
                13, TextDim
            )
        }
    }
}
