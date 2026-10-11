package uz.monitorcheck.ui.flicker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import uz.monitorcheck.analysis.standards.Ieee1789
import uz.monitorcheck.ui.theme.StatusColors
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/** Qator profili (nisbiy yorug'lik, o'rtacha = 1) — jonli ko'rinish uchun. */
@Composable
fun ProfileChart(values: FloatArray, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(90.dp)) {
        if (values.size < 2) return@Canvas
        var lo = values.min()
        var hi = values.max()
        val span = max(hi - lo, 0.02f)           // kamida ±1% shkala — shovqin kattalashib ko'rinmasin
        val mid = (hi + lo) / 2
        lo = mid - span / 2
        hi = mid + span / 2
        drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
        val p = Path()
        values.forEachIndexed { i, v ->
            val x = i * size.width / (values.size - 1)
            val y = size.height * (1 - (v - lo) / (hi - lo))
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, line, style = Stroke(width = 2.dp.toPx()))
    }
}

/** Bir davr to'lqin shakli (nisbiy). */
@Composable
fun WaveformChart(waveform: DoubleArray, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    Canvas(modifier.fillMaxWidth().height(100.dp)) {
        if (waveform.isEmpty()) return@Canvas
        val hi = max(waveform.max(), 1.0) * 1.05
        drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height))
        val p = Path()
        // Ikki davr ko'rsatiladi
        val n = waveform.size * 2
        for (i in 0..n) {
            val v = waveform[i % waveform.size]
            val x = i * size.width / n
            val y = (size.height * (1 - v / hi)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, line, style = Stroke(width = 2.dp.toPx()))
    }
}

/**
 * IEEE 1789-2015 diagrammasi: gorizontal — chastota (log, 50 Hz…20 kHz), vertikal — Mod%.
 * Yashil — NOEL zonasi, sariq — past xavf, qizil — tavsiya chegarasidan tashqari.
 * O'lchov nuqtasi 95% ishonch oralig'i chiziqlari bilan.
 */
@Composable
fun Ieee1789Chart(
    fHz: Double?,
    modPct: Double?,
    fLow: Double?,
    fHigh: Double?,
    modLow: Double?,
    modHigh: Double?,
    labelNoel: String,
    labelLow: String,
    modifier: Modifier = Modifier,
) {
    val text = MaterialTheme.colorScheme.onSurface
    val point = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.fillMaxWidth().height(220.dp)) {
        val fMin = 50.0
        val fMax = 20000.0
        val left = 36.dp.toPx()
        val bottom = 22.dp.toPx()
        val w = size.width - left
        val h = size.height - bottom
        fun x(f: Double) = (left + w * (log10(f) - log10(fMin)) / (log10(fMax) - log10(fMin))).toFloat()
        fun y(m: Double) = (h * (1 - min(m, 100.0) / 100.0)).toFloat()

        fillUnder(::x, ::y, fMin, fMax, h, StatusColors.bad.copy(alpha = 0.18f)) { 100.0 }
        fillUnder(::x, ::y, fMin, fMax, h, StatusColors.caution.copy(alpha = 0.25f)) { Ieee1789.lowRiskLimitPct(it) }
        fillUnder(::x, ::y, fMin, fMax, h, StatusColors.ok.copy(alpha = 0.28f)) { Ieee1789.noelLimitPct(it) }

        // O'qlar va belgilar
        drawLine(text, Offset(left, 0f), Offset(left, h))
        drawLine(text, Offset(left, h), Offset(size.width, h))
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(
                (text.alpha * 255).toInt(), (text.red * 255).toInt(), (text.green * 255).toInt(), (text.blue * 255).toInt())
            textSize = 10.dp.toPx()
            isAntiAlias = true
        }
        for (f in listOf(100.0, 300.0, 1000.0, 3000.0, 10000.0)) {
            val label = if (f >= 1000) "${(f / 1000).toInt()}k" else f.toInt().toString()
            drawContext.canvas.nativeCanvas.drawText(label, x(f) - 8.dp.toPx(), size.height - 6.dp.toPx(), paint)
            drawLine(text.copy(alpha = 0.2f), Offset(x(f), 0f), Offset(x(f), h))
        }
        for (m in listOf(25.0, 50.0, 75.0, 100.0)) {
            drawContext.canvas.nativeCanvas.drawText("${m.toInt()}%", 2.dp.toPx(), y(m) + 4.dp.toPx(), paint)
            drawLine(text.copy(alpha = 0.2f), Offset(left, y(m)), Offset(size.width, y(m)))
        }
        drawContext.canvas.nativeCanvas.drawText(labelNoel, x(400.0), y(6.0), paint)
        drawContext.canvas.nativeCanvas.drawText(labelLow, x(150.0), y(30.0), paint)

        // O'lchov nuqtasi
        if (fHz != null && modPct != null) {
            val px = x(fHz.coerceIn(fMin, fMax))
            val py = y(modPct)
            if (modLow != null && modHigh != null) drawLine(point, Offset(px, y(max(0.0, modLow))), Offset(px, y(modHigh)), 2f)
            if (fLow != null && fHigh != null) {
                drawLine(point, Offset(x(fLow.coerceIn(fMin, fMax)), py), Offset(x(fHigh.coerceIn(fMin, fMax)), py), 2f)
            }
            drawCircle(point, 5.dp.toPx(), Offset(px, py))
        }
    }
}

private fun DrawScope.fillUnder(
    x: (Double) -> Float,
    y: (Double) -> Float,
    fMin: Double,
    fMax: Double,
    baseY: Float,
    color: Color,
    limit: (Double) -> Double,
) {
    val p = Path()
    p.moveTo(x(fMin), baseY)
    val steps = 200
    for (i in 0..steps) {
        val f = fMin * Math.pow(fMax / fMin, i.toDouble() / steps)
        p.lineTo(x(f), y(min(limit(f), 100.0)))
    }
    p.lineTo(x(fMax), baseY)
    p.close()
    drawPath(p, color)
}
