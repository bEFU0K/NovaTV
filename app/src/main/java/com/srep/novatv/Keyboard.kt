package com.srep.novatv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val RU_ROWS = listOf("1234567890", "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю.")
private val EN_ROWS = listOf("1234567890", "qwertyuiop", "asdfghjkl-", "zxcvbnm_.@")

@Composable
private fun KbKey(label: String, w: Int, primary: Boolean = false, autoFocus: Boolean = false, onClick: () -> Unit) {
    val accent = LocalAccent.current
    FocusCard(Modifier.width(w.dp).height(52.dp), RoundedCornerShape(10.dp), autoFocus, null, onClick) { f ->
        Box(Modifier.fillMaxSize().background(if (primary) accent.copy(alpha = 0.35f) else androidx.compose.ui.graphics.Color.Transparent), contentAlignment = Alignment.Center) {
            Txt(label, 20, if (f) TextMain else TextDim, bold = f, maxLines = 1)
        }
    }
}

/** Экранная клавиатура под пульт: стрелки двигают фокус, OK нажимает клавишу. Русская и английская раскладки. */
@Composable
fun OnScreenKeyboard(
    onChar: (String) -> Unit, onBackspace: () -> Unit, onEnter: () -> Unit,
    enterLabel: String = "ОК", autoFocus: Boolean = true
) {
    var ru by remember { mutableStateOf(true) }
    var shift by remember { mutableStateOf(false) }
    val rows = if (ru) RU_ROWS else EN_ROWS
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEachIndexed { ri, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { ci, ch ->
                    val s = if (shift) ch.toString().uppercase() else ch.toString()
                    KbKey(s, 52, autoFocus = autoFocus && ri == 1 && ci == 0) { onChar(s); if (shift) shift = false }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KbKey(if (ru) "RU" else "EN", 84) { ru = !ru }
            KbKey(if (shift) "⇧ вкл" else "⇧", 84) { shift = !shift }
            KbKey("Пробел", 232) { onChar(" ") }
            KbKey("⌫", 84) { onBackspace() }
            KbKey(enterLabel, 110, primary = true) { onEnter() }
        }
    }
}

/** Окно ввода текста с экранной клавиатурой. onResult(null) — отмена. */
@Composable
fun TextEntryDialog(title: String, initial: String = "", enterLabel: String = "Готово", onResult: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = { onResult(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(780.dp).background(Graphite, RoundedCornerShape(20.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Txt(title, 24, bold = true)
            Box(Modifier.fillMaxWidth().background(Bg, RoundedCornerShape(10.dp)).padding(14.dp)) { Txt(text + "▏", 24) }
            OnScreenKeyboard({ text += it }, { text = text.dropLast(1) }, { onResult(text) }, enterLabel)
            FocusButton("Отмена") { onResult(null) }
        }
    }
}
