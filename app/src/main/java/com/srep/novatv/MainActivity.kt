package com.srep.novatv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private var homeSignal by mutableIntStateOf(0)
    private var resumeSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NovaApp(homeSignal, resumeSignal) }
    }

    override fun onResume() {
        super.onResume()
        resumeSignal++ // вернулись из другого приложения — заново отдаём фокус плиткам
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) resumeSignal++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) homeSignal++ // кнопка Home → на главный экран
    }
}

@Composable
fun NovaApp(homeSignal: Int, resumeSignal: Int = 0) {
    val ctx = LocalContext.current
    val ui = remember { UiState() }
    val apps by AppRepo.apps.collectAsState()
    val net by NetMonitor.state.collectAsState()
    val vpn by VpnMonitor.active.collectAsState()
    val session by SessionStore.flow.collectAsState()
    val manifestTick by Provision.manifestTick.collectAsState()

    var screen by remember {
        mutableStateOf(
            when {
                !Prefs.setupDone -> Screen.Setup
                SessionStore.flow.value == null -> Screen.Login
                !Prefs.provisionDone -> Screen.Provision
                else -> Screen.Home
            }
        )
    }
    var restoreShown by remember { mutableStateOf(false) }

    // Вход только по QR: пока нет сессии, доступны лишь первичная настройка и экран QR
    // (и две служебные страницы — интернет и Bluetooth, чтобы можно было подключиться).
    val shown = when {
        screen == Screen.Setup -> Screen.Setup
        session == null && screen != Screen.Network && screen != Screen.Bluetooth -> Screen.Login
        else -> screen
    }

    LaunchedEffect(homeSignal) { if (homeSignal > 0 && Prefs.setupDone && session != null) screen = Screen.Home }
    LaunchedEffect(Unit) { AppRepo.reload(ctx.applicationContext) }
    // Автовосстановление сессии: обновляем токен до истечения; при сетевой ошибке сессию не трогаем.
    LaunchedEffect(session?.access, net.online) {
        val s = session ?: return@LaunchedEffect
        if (s.expiresAt - System.currentTimeMillis() > 60_000) return@LaunchedEffect
        if (s.refresh == null) { if (s.expiresAt < System.currentTimeMillis()) SessionStore.clear(); return@LaunchedEffect }
        if (!net.online) return@LaunchedEffect
        try {
            val n = Portal.refresh(s)
            if (n != null) SessionStore.save(n) else SessionStore.clear()
        } catch (e: Exception) {
            AppLog.w("portal", "refresh", e)
        }
    }

    // Если системное приложение удалили — предлагаем сразу скачать и поставить его заново.
    // После обновления Nova (и при каждом запуске) тихо сверяем список приложений с сайтом: новое — предложим поставить.
    LaunchedEffect(session?.user?.id, net.online) {
        if (session == null || !net.online || !Prefs.provisionDone) return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Provision.refreshManifest(ctx.applicationContext) }
    }
    LaunchedEffect(apps, manifestTick) {
        if (!Prefs.setupDone || session == null || apps == null || !Prefs.provisionDone) return@LaunchedEffect
        val m = Provision.missing(ctx)
        if (m.isEmpty()) restoreShown = false
        else if (!restoreShown && screen == Screen.Home) { restoreShown = true; screen = Screen.Provision }
    }

    BackHandler(enabled = true) {
        when {
            shown == Screen.Setup -> {}
            shown == Screen.Login && session == null -> {}
            shown == Screen.Home -> {}
            else -> screen = Screen.Home
        }
    }

    val home = { screen = Screen.Home }
    val afterLogin = { screen = if (!Prefs.provisionDone) Screen.Provision else Screen.Home }
    CompositionLocalProvider(LocalAccent provides Accents[ui.accent]) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            Wallpaper(ui.wallpaper, ui.animate)
            AnimatedContent(
                targetState = shown,
                transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(90)) },
                label = "screens"
            ) { s ->
                when (s) {
                    Screen.Setup -> SetupScreen(net) { Prefs.setupDone = true; screen = if (session == null) Screen.Login else if (!Prefs.provisionDone) Screen.Provision else Screen.Home }
                    Screen.Login -> LoginScreen(session, net, onDone = afterLogin, back = if (session != null) home else null, go = { screen = it })
                    Screen.Provision -> ProvisionScreen(session, onDone = home, back = home)
                    Screen.Home -> HomeScreen(ui, apps, net, vpn, session?.user) { screen = it }
                    Screen.Apps -> AppsScreen(ui, apps, goApk = { screen = Screen.Apk }, back = home)
                    Screen.Apk -> ApkScreen(home)
                    Screen.Vpn -> VpnScreen(vpn, net, goApk = { screen = Screen.Apk }, back = home)
                    Screen.Settings -> SettingsScreen(ui, session, go = { screen = it }, back = home)
                    Screen.About -> AboutScreen(home)
                    Screen.Update -> UpdateScreen(home)
                    Screen.Diagnostics -> DiagnosticsScreen(net, vpn, home)
                    Screen.Files -> FilesScreen(home)
                    Screen.Bluetooth -> BluetoothScreen(back = { screen = if (session == null) Screen.Login else Screen.Home })
                    Screen.Network -> NetworkScreen(net, back = { screen = if (session == null) Screen.Login else Screen.Home })
                }
            }
            if (!net.online && shown != Screen.Setup && shown != Screen.Diagnostics && shown != Screen.Network) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).background(Danger, RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 6.dp)) {
                    Txt("Нет подключения к интернету — Настройки → Интернет", 14, Bg, bold = true)
                }
            }
        }
    }
}
