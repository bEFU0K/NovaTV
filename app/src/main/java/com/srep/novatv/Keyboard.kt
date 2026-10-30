package com.srep.novatv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** Окно ввода текста. Клавиатуру показывает сама система Android. onResult(null) — отмена. */
@Composable
fun TextEntryDialog(title: String, initial: String = "", enterLabel: String = "Готово", onResult: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val fr = remember { FocusRequester() }
    Dialog(onDismissRequest = { onResult(null) }) {
        Column(
            Modifier.width(560.dp).background(Graphite, RoundedCornerShape(20.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Txt(title, 24, bold = true)
            BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = TextStyle(color = TextMain, fontSize = 24.sp),
                cursorBrush = SolidColor(TextMain),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onResult(text) }),
                modifier = Modifier.fillMaxWidth().background(Bg, RoundedCornerShape(10.dp)).padding(14.dp).focusRequester(fr)
            )
            FocusButton(enterLabel, Modifier.fillMaxWidth(), primary = true) { onResult(text) }
            FocusButton("Отмена", Modifier.fillMaxWidth()) { onResult(null) }
            LaunchedEffect(Unit) { try { fr.requestFocus() } catch (_: Exception) { } }
        }
    }
}
