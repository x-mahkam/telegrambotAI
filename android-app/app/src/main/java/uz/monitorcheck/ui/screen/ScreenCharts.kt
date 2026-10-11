package uz.monitorcheck.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import uz.monitorcheck.analysis.image.Pt
import uz.monitorcheck.analysis.screen.Defect
import uz.monitorcheck.analysis.screen.DefectKind
import uz.monitorcheck.ui.theme.StatusColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Og'ishga qarab rang: 0% — yashil, ≥ 30% — qizil. */
private fun deviationColor(devPct: Double): Color {
    val t = (abs(devPct) / 30.0).coerceIn(0.0, 1.0).toFloat()
    return if (t < 0.5f) lerp(StatusColors.ok, StatusColors.caution, t * 2) else lerp(StatusColors.caution, StatusColors.bad, (t - 0.5f) * 2)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.label(text: String, x: Float, y: Float, color: Color, sizeDp: Float = 13f, center: Boolean = true) {
    val p = android.graphics.Paint().apply {
        this.color = color.toArgb()
        textSize = sizeDp * density
        isAntiAlias = true
        isFakeBoldText = true
        textAlign = if (center) android.graphics.Paint.Align.CENTER else android.graphics.Paint.Align.LEFT
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y + p.textSize / 3, p)
}

/** 3×3 zona: markazga nisbatan foiz. */
@Composable
fun UniformityGrid(relativePct: List<Double>, aspect: Float = 16f / 9f) {
    Canvas(Modifier.fillMaxWidth().aspectRatio(aspect)) {
        val cw = size.width / 3
        val ch = size.height / 3
        for (r in 0 until 3) for (c in 0 until 3) {
            val v = relativePct[r * 3 + c]
            val col = deviationColor(v - 100)
            drawRoundRect(col.copy(alpha = 0.85f), Offset(c * cw + 2, r * ch + 2), Size(cw - 4, ch - 4), CornerRadius(10f, 10f))
            label("%.0f%%".format(v), c * cw + cw / 2, r * ch + ch / 2, Color.White, 15f)
        }
    }
}

/** Ekran xaritasi va topilgan nuqsonlar. */
@Composable
fun DefectMap(defects: List<Defect>, aspect: Float = 16f / 9f) {
    val frame = MaterialTheme.colorScheme.outline
    val bg = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().aspectRatio(aspect)) {
        drawRoundRect(bg, cornerRadius = CornerRadius(12f, 12f))
        drawRoundRect(frame, cornerRadius = CornerRadius(12f, 12f), style = Stroke(2f))
        for (i in 1 until 3) {
            drawLine(frame.copy(alpha = 0.3f), Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height))
            drawLine(frame.copy(alpha = 0.3f), Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3))
        }
        for (d in defects) {
            val c = when (d.kind) {
                DefectKind.DARK_PIXEL -> Color(0xFF111827)
                DefectKind.BRIGHT_PIXEL -> Color(0xFFF59E0B)
                DefectKind.SUBPIXEL -> Color(0xFFDB2777)
                DefectKind.SPOT -> Color(0xFF64748B)
            }
            val o = Offset((d.u * size.width).toFloat(), (d.v * size.height).toFloat())
            val r = if (d.kind == DefectKind.SPOT) 10f else 7f
            drawCircle(Color.White, r + 3, o)
            drawCircle(c, r, o)
        }
    }
}

/** Qora ekran yorqinligi xaritasi (markaz = 1). */
@Composable
fun BleedHeatmap(map: List<Double>, cols: Int, rows: Int, aspect: Float = 16f / 9f) {
    Canvas(Modifier.fillMaxWidth().aspectRatio(aspect)) {
        val cw = size.width / cols
        val ch = size.height / rows
        for (r in 0 until rows) for (c in 0 until cols) {
            val v = map[r * cols + c]
            val col = if (v.isNaN()) Color(0xFF334155) else {
                val t = ((v - 1.0) / 3.0).coerceIn(0.0, 1.0).toFloat()
                lerp(Color(0xFF0F172A), Color(0xFFFBBF24), t)
            }
            drawRect(col, Offset(c * cw, r * ch), Size(cw + 0.5f, ch + 0.5f))
            if (!v.isNaN() && (r == 0 || c == 0 || r == rows - 1 || c == cols - 1) && v >= 1.5) {
                label("%.1f".format(v), c * cw + cw / 2, r * ch + ch / 2, Color.White, 10f)
            }
        }
    }
}

/** Gradient pog'onalari: har bir katak o'rtacha yorqinligi (normallashtirilgan). */
@Composable
fun BandingChart(dark: List<Double>, bright: List<Double>) {
    val c1 = MaterialTheme.colorScheme.primary
    val c2 = MaterialTheme.colorScheme.tertiary
    val axis = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
        fun drawSeries(v: List<Double>, color: Color, top: Float, h: Float) {
            if (v.isEmpty()) return
            val lo = v.min()
            val hi = max(v.max(), lo + 1e-9)
            val p = Path()
            val n = v.size
            for (i in 0 until n) {
                val x0 = size.width * i / n
                val x1 = size.width * (i + 1) / n
                val y = top + h * (1 - ((v[i] - lo) / (hi - lo)).toFloat())
                if (i == 0) p.moveTo(x0, y) else p.lineTo(x0, y)
                p.lineTo(x1, y)
            }
            drawPath(p, color, style = Stroke(width = 2.dp.toPx()))
            drawLine(axis.copy(alpha = 0.4f), Offset(0f, top + h), Offset(size.width, top + h))
        }
        val h = size.height / 2 - 8
        drawSeries(bright, c1, 0f, h)
        drawSeries(dark, c2, size.height / 2 + 8, h)
    }
}

/** Jonli ko'rinish ustida ekran konturi (ko'rinish koordinatalari 0..1). */
@Composable
fun OutlineOverlay(points: List<Pt>, ready: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        if (points.isEmpty()) return@Canvas
        val col = if (ready) StatusColors.ok else StatusColors.caution
        if (ready && points.size == 4) {
            val p = Path()
            points.forEachIndexed { i, q ->
                val o = Offset((q.x * size.width).toFloat(), (q.y * size.height).toFloat())
                if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y)
            }
            p.close()
            drawPath(p, col.copy(alpha = 0.18f))
            drawPath(p, col, style = Stroke(width = 3.dp.toPx()))
        }
        for (q in points) drawCircle(col, 7.dp.toPx(), Offset((q.x * size.width).toFloat(), (q.y * size.height).toFloat()))
        val minDim = min(size.width, size.height)
        if (minDim <= 0f) return@Canvas
    }
}
