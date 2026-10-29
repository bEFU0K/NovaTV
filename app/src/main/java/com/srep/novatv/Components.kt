package com.srep.novatv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

val Bg = Color(0xFF060B16)
val Graphite = Color(0xFF101A30)
val Surface = Color(0xB3121C36)
val SurfaceFocus = Color(0xE62B3F6B)
val TextMain = Color(0xFFF2F5FB)
val TextDim = Color(0xFFA9B6CE)
val Danger = Color(0xFFFF6B6B)
val Accents = listOf(Color(0xFF3D8BFF), Color(0xFF8B5CF6), Color(0xFF14D8C4))
val AccentNames = listOf("Синий", "Фиолетовый", "Бирюзовый")
val WallpaperNames = listOf("Фон + синее свечение", "Фон + фиолетовое свечение", "Фон + бирюзовое свечение")
val CardSizeNames = listOf("Компактные", "Средние", "Крупные")
val LocalAccent = compositionLocalOf { Accents[0] }

enum class Screen { Setup, Login, Provision, Home, Apps, Apk, Vpn, Settings, About, Update, Diagnostics, Files, Bluetooth, Volume, Network, Keyboard }

class UiState {
    var accent by mutableIntStateOf(Prefs.accent)
    var wallpaper by mutableIntStateOf(Prefs.wallpaper)
    var cardSize by mutableIntStateOf(Prefs.cardSize)
    var animate by mutableStateOf(Prefs.animate)
    var favorites by mutableStateOf(Prefs.favorites)
    var recents by mutableStateOf(Prefs.recents)
    var edit by mutableStateOf(false)

    val cardW: Dp get() = listOf(130, 160, 190)[cardSize].dp

    fun cycleAccent() { accent = (accent + 1) % Accents.size; Prefs.accent = accent }
    fun cycleWallpaper() { wallpaper = (wallpaper + 1) % WallpaperNames.size; Prefs.wallpaper = wallpaper }
    fun cycleCards() { cardSize = (cardSize + 1) % CardSizeNames.size; Prefs.cardSize = cardSize }
    fun toggleAnim() { animate = !animate; Prefs.animate = animate }

    fun toggleFav(pkg: String) {
        favorites = if (pkg in favorites) favorites - pkg else favorites + pkg
        Prefs.favorites = favorites
    }

    fun move(pkg: String, d: Int) {
        val l = favorites.toMutableList()
        val i = l.indexOf(pkg)
        val j = i + d
        if (i < 0 || j !in l.indices) return
        l[i] = l[j].also { l[j] = l[i] }
        favorites = l
        Prefs.favorites = l
    }

    fun pushRecent(pkg: String) {
        recents = (listOf(pkg) + recents.filter { it != pkg }).take(10)
        Prefs.recents = recents
    }

    fun reset() {
        Prefs.resetUi()
        accent = 0; wallpaper = 0; cardSize = 1; animate = true
        favorites = emptyList(); recents = emptyList(); edit = false
    }
}

@Composable
fun Txt(
    t: String, size: Int = 18, color: Color = TextMain, bold: Boolean = false,
    modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE
) {
    Text(
        text = t, modifier = modifier, color = color, fontSize = size.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis
    )
}

/** Фокусируемая карточка: видимая рамка фокуса + плавное увеличение. Работает со стрелками и OK пульта. */
@Composable
fun FocusCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    autoFocus: Boolean = false,
    onKey: ((KeyEvent) -> Boolean)? = null,
    onClick: () -> Unit,
    content: @Composable BoxScope.(Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(110), label = "focusScale")
    val accent = LocalAccent.current
    val bgColor by animateColorAsState(if (focused) SurfaceFocus else Surface, tween(100), label = "focusBg")
    val fr = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { try { fr.requestFocus() } catch (_: Exception) { } }
    var m = modifier.zIndex(if (focused) 1f else 0f).scale(scale).focusRequester(fr)
    if (onKey != null) m = m.onPreviewKeyEvent(onKey)
    Box(
        m.onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(bgColor)
            .border(if (focused) 3.dp else 0.dp, if (focused) accent else Color.Transparent, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) { content(focused) }
}

@Composable
fun FocusButton(
    text: String, modifier: Modifier = Modifier, autoFocus: Boolean = false,
    primary: Boolean = false, onClick: () -> Unit
) {
    val accent = LocalAccent.current
    FocusCard(modifier, RoundedCornerShape(12.dp), autoFocus, null, onClick) { f ->
        Box(Modifier.background(if (primary) accent.copy(alpha = 0.35f) else Color.Transparent)) {
            Txt(text, 18, if (f) TextMain else TextDim, bold = f, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp))
        }
    }
}

@Composable
fun Chip(text: String, on: Boolean) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Surface).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) Color(0xFF3DDC84) else Danger))
        Spacer(Modifier.width(8.dp))
        Txt(text, 15, TextMain)
    }
}

@Composable
fun Wallpaper(idx: Int, animate: Boolean) {
    val t = if (animate) {
        val tr = rememberInfiniteTransition(label = "wall")
        tr.animateFloat(0f, 1f, infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Reverse), label = "p").value
    } else 0.5f
    val glow = Accents[idx % Accents.size]
    Box(Modifier.fillMaxSize().background(Bg)) {
        // Фон — картинка пользователя (res/drawable-nodpi/bg_nova.png)
        Image(painterResource(R.drawable.bg_nova), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        // Лёгкое «дыхание» света поверх фона — плавно, без нагрузки на ТВ
        Canvas(Modifier.fillMaxSize()) {
            val r = size.width * 0.55f
            val c = Offset(size.width * (0.1f + 0.35f * t), size.height * (0.1f + 0.1f * t))
            drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.16f), Color.Transparent), c, r), r, c)
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Bg.copy(alpha = 0.55f))))
        }
    }
}

@Composable
fun ScreenFrame(title: String, back: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (back != null) {
                FocusButton("← Назад", onClick = back)
                Spacer(Modifier.width(24.dp))
            }
            Txt(title, 34, bold = true)
        }
        Spacer(Modifier.height(24.dp))
        content()
    }
}

/** Состояния загрузки / ошибки / восстановления соединения. */
@Composable
fun StatusPane(text: String, error: Boolean = false, loading: Boolean = false, retryLabel: String = "Повторить", retry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (loading) CircularProgressIndicator(color = LocalAccent.current)
        Txt(text, 22, if (error) Danger else TextMain)
        if (retry != null) FocusButton(retryLabel, primary = true, autoFocus = true, onClick = retry)
    }
}
