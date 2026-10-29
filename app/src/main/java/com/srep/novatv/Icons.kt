package com.srep.novatv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class Ic { Menu, Apps, Files, Install, Lock, Update, Gear, Volume, Wifi, Bluetooth, Shield, Keyboard, Info, User }

/** Собственные иконки Nova: рисуются линиями на сетке 24×24, поэтому чёткие на любом экране. Акцентным цветом выделена одна деталь. */
@Composable
fun NovaIcon(ic: Ic, size: Dp = 28.dp, tint: Color = TextMain, accent: Color = LocalAccent.current) {
    Canvas(Modifier.size(size)) {
        val k = this.size.minDimension / 24f
        val st = Stroke(width = 1.9f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * k, y * k)
        fun poly(vararg p: Float, close: Boolean = false, c: Color = tint) {
            val path = Path()
            path.moveTo(p[0] * k, p[1] * k)
            var i = 2
            while (i < p.size) { path.lineTo(p[i] * k, p[i + 1] * k); i += 2 }
            if (close) path.close()
            drawPath(path, c, style = st)
        }
        fun arc(x: Float, y: Float, w: Float, h: Float, start: Float, sweep: Float, c: Color = tint) =
            drawArc(c, start, sweep, false, o(x, y), Size(w * k, h * k), style = st)
        fun box(x: Float, y: Float, w: Float, h: Float, r: Float, c: Color = tint) =
            drawRoundRect(c, o(x, y), Size(w * k, h * k), CornerRadius(r * k), style = st)
        fun dot(x: Float, y: Float, r: Float, c: Color = tint) = drawCircle(c, r * k, o(x, y))

        when (ic) {
            Ic.Menu -> { poly(4f, 6f, 20f, 6f); poly(4f, 12f, 15f, 12f, c = accent); poly(4f, 18f, 20f, 18f) }
            Ic.Apps -> { box(4f, 4f, 7f, 7f, 2f, accent); box(13f, 4f, 7f, 7f, 2f); box(4f, 13f, 7f, 7f, 2f); box(13f, 13f, 7f, 7f, 2f) }
            Ic.Files -> { poly(3f, 6f, 9f, 6f, 11f, 8f, 21f, 8f, 21f, 19f, 3f, 19f, close = true); poly(7f, 13f, 17f, 13f, c = accent) }
            Ic.Install -> { poly(12f, 4f, 12f, 14f, c = accent); poly(8f, 10f, 12f, 14f, 16f, 10f, c = accent); poly(5f, 15f, 5f, 19f, 19f, 19f, 19f, 15f) }
            Ic.Lock -> { box(6f, 11f, 12f, 9f, 2f); arc(8f, 4f, 8f, 8f, 180f, 180f); poly(8f, 8f, 8f, 11f); poly(16f, 8f, 16f, 11f); dot(12f, 15.5f, 1.2f, accent) }
            Ic.Update -> { arc(5f, 5f, 14f, 14f, 40f, 280f); poly(18f, 3.5f, 18f, 8f, 13.5f, 8f, c = accent) }
            Ic.Gear -> {
                arc(6f, 6f, 12f, 12f, 0f, 360f); dot(12f, 12f, 2.2f, accent)
                val a = floatArrayOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)
                for (d in a) {
                    val r = Math.toRadians(d.toDouble())
                    val c = Math.cos(r).toFloat(); val s = Math.sin(r).toFloat()
                    poly(12f + 7f * c, 12f + 7f * s, 12f + 9.5f * c, 12f + 9.5f * s)
                }
            }
            Ic.Volume -> { poly(4f, 9f, 8f, 9f, 13f, 5f, 13f, 19f, 8f, 15f, 4f, 15f, close = true); arc(9f, 8f, 8f, 8f, -45f, 90f, accent); arc(5f, 4f, 16f, 16f, -45f, 90f) }
            Ic.Wifi -> { arc(8f, 15f, 8f, 8f, 225f, 90f, accent); arc(4f, 11f, 16f, 16f, 225f, 90f); arc(0f, 7f, 24f, 24f, 225f, 90f); dot(12f, 19f, 1.3f, accent) }
            Ic.Bluetooth -> { poly(7f, 8f, 17f, 16f, 12f, 21f, 12f, 3f, 17f, 8f, 7f, 16f); dot(12f, 12f, 1f, accent) }
            Ic.Shield -> { poly(12f, 3f, 19f, 6f, 19f, 12f, 12f, 21f, 5f, 12f, 5f, 6f, close = true); poly(9f, 12f, 11f, 14f, 15f, 10f, c = accent) }
            Ic.Keyboard -> {
                box(3f, 6f, 18f, 12f, 2.5f)
                for (x in floatArrayOf(7f, 10f, 14f, 17f)) dot(x, 10f, 0.9f, accent)
                poly(8f, 14.5f, 16f, 14.5f)
            }
            Ic.Info -> { arc(3f, 3f, 18f, 18f, 0f, 360f); dot(12f, 8f, 1f, accent); poly(12f, 11f, 12f, 16f) }
            Ic.User -> { arc(8.5f, 4.5f, 7f, 7f, 0f, 360f, accent); arc(5f, 14f, 14f, 14f, 180f, 180f) }
        }
    }
}
