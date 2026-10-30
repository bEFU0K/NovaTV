package com.srep.novatv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
private fun IconTile(ic: Ic, title: String, sub: String, onClick: () -> Unit) {
    val accent = LocalAccent.current
    FocusCard(Modifier.width(260.dp), RoundedCornerShape(22.dp), onClick = onClick) { f ->
        Box(Modifier.background(Brush.linearGradient(listOf(accent.copy(alpha = if (f) 0.38f else 0.18f), Color.Transparent)))) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier.size(60.dp).background(accent.copy(alpha = if (f) 0.50f else 0.26f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) { NovaIcon(ic, 34.dp) }
                Column {
                    Txt(title, 22, bold = true, maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    Txt(sub, 14, TextDim, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(width = 5.dp, height = 26.dp).background(LocalAccent.current, RoundedCornerShape(3.dp)))
        Txt(text, 26, bold = true)
        trailing?.invoke(this)
    }
}

private fun greeting(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        h in 5..11 -> "Доброе утро"
        h in 12..17 -> "Добрый день"
        h in 18..22 -> "Добрый вечер"
        else -> "Доброй ночи"
    }
}

@Composable
private fun PanelRow(ic: Ic, title: String, sub: String, autoFocus: Boolean = false, onClick: () -> Unit) {
    FocusCard(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), autoFocus, null, onClick) { f ->
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            NovaIcon(ic, 28.dp)
            Column(Modifier.weight(1f)) {
                Txt(title, 19, if (f) TextMain else TextMain, bold = true, maxLines = 1)
                if (sub.isNotBlank()) Txt(sub, 12, TextDim, maxLines = 1)
            }
        }
    }
}

@Composable
fun HomeScreen(ui: UiState, apps: List<AppItem>?, net: NetState, vpn: Boolean, user: PortalUser?, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val byPkg = remember(apps) { apps.orEmpty().associateBy { it.pkg } }
    val favs = ui.favorites.mapNotNull { byPkg[it] }
    val recents = ui.recents.mapNotNull { byPkg[it] }
    var panel by remember { mutableStateOf(false) }
    val menuFr = remember { FocusRequester() }
    var wasOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = panel) { panel = false }
    LaunchedEffect(panel) {
        if (!panel && wasOpen) { try { menuFr.requestFocus() } catch (_: Exception) { } }
        wasOpen = panel
    }
    val open: (Screen) -> Unit = { s -> panel = false; go(s) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().focusProperties { canFocus = !panel }.verticalScroll(rememberScrollState()).padding(horizontal = 56.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Clock()
                    Txt(greeting() + (if (user != null) ", ${user.name}" else ""), 20, TextDim, maxLines = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(if (net.online) net.type else "Нет сети", net.online)
                    Chip(if (vpn) "VPN включён" else "VPN выключен", vpn)
                    FocusCard(Modifier.size(56.dp).focusRequester(menuFr), RoundedCornerShape(16.dp), onClick = { panel = true }) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { NovaIcon(Ic.Menu, 30.dp) }
                    }
                }
            }
            SectionTitle("Избранное") {
                FocusButton(if (ui.edit) "Готово" else "Изменить порядок") { ui.edit = !ui.edit }
                if (ui.edit) Txt("← → переместить выбранное приложение", 15, TextDim)
            }
            if (favs.isEmpty()) {
                Box(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).padding(22.dp)) {
                    Txt("Пока пусто. Откройте «Все приложения» и выберите «В избранное».", 18, TextDim)
                }
            } else LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                items(favs, key = { it.pkg }) { a ->
                    AppCard(a, ui.cardW, onKey = { e ->
                        if (ui.edit && e.type == KeyEventType.KeyDown && (e.key == Key.DirectionLeft || e.key == Key.DirectionRight)) {
                            ui.move(a.pkg, if (e.key == Key.DirectionLeft) -1 else 1); true
                        } else false
                    }) { if (!ui.edit) launchApp(ctx, ui, a) }
                }
            }
            if (recents.isNotEmpty()) {
                SectionTitle("Недавние")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                    items(recents, key = { it.pkg }) { a -> AppCard(a, ui.cardW) { launchApp(ctx, ui, a) } }
                }
            }
            SectionTitle("Быстрый доступ")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                item { IconTile(Ic.Apps, "Все приложения", "Запуск, избранное") { go(Screen.Apps) } }
                item { IconTile(Ic.Files, "Файлы", "Память, USB") { go(Screen.Files) } }
                item { IconTile(Ic.Install, "Установка APK", "APK, XAPK, APKS") { go(Screen.Apk) } }
                item { IconTile(Ic.Wifi, "Интернет", if (net.online) net.type else "Нет сети") { go(Screen.Network) } }
                item { IconTile(Ic.Lock, "Системные", if (Prefs.provisionDone) "Установлены" else "Нужна установка") { go(Screen.Provision) } }
                item { IconTile(Ic.Gear, "Настройки", "Фон, акцент, система") { go(Screen.Settings) } }
            }
        }

        AnimatedVisibility(panel, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize().background(Color(0x99030812)))
        }
        AnimatedVisibility(
            panel, modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(320)) { it } + fadeIn(tween(220)),
            exit = slideOutHorizontally(tween(260)) { it } + fadeOut(tween(200))
        ) {
            Column(
                Modifier.width(400.dp).fillMaxHeight()
                    .background(Brush.verticalGradient(listOf(Color(0xF2122244), Color(0xF20A1226))), RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                    .verticalScroll(rememberScrollState()).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FocusCard(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp), autoFocus = true, onClick = { open(Screen.Login) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(
                            Modifier.size(52.dp).background(Brush.linearGradient(listOf(Accents[0], Accents[1])), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (user != null) Txt(user.name.take(1).uppercase(), 24, bold = true) else NovaIcon(Ic.User, 28.dp)
                        }
                        Column {
                            Txt(user?.name ?: "Аккаунт", 21, bold = true, maxLines = 1)
                            Txt(if (user != null) "Srepportal · управление входом" else "Войти по QR", 13, TextDim, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                PanelRow(Ic.Wifi, "Интернет", if (net.online) net.type else "Нет сети") { open(Screen.Network) }
                PanelRow(Ic.Bluetooth, "Bluetooth", "Пульты, наушники") { open(Screen.Bluetooth) }
                PanelRow(Ic.Shield, "VPN", if (vpn) "Включён" else "Выключен") { open(Screen.Vpn) }
                PanelRow(Ic.Info, "Об устройстве", "CPU, ОЗУ, память") { open(Screen.About) }
                PanelRow(Ic.Gear, "Все настройки", "") { open(Screen.Settings) }
            }
        }
    }
}
