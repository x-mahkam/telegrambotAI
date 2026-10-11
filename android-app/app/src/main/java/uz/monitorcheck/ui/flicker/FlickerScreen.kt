package uz.monitorcheck.ui.flicker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import uz.monitorcheck.R
import uz.monitorcheck.analysis.dsp.Estimate
import uz.monitorcheck.analysis.flicker.FlickerWarning
import uz.monitorcheck.analysis.flicker.TimingSource
import uz.monitorcheck.analysis.flicker.WaveformModel
import uz.monitorcheck.analysis.standards.Ieee1789Level
import uz.monitorcheck.ui.theme.StatusColors
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun FlickerScreen(onBack: () -> Unit, vm: FlickerViewModel = viewModel()) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    // O'lchov paytida ekran o'chmasin
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Scaffold { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.back)) }
                Text(stringResource(R.string.flicker_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (!granted) {
                InfoCard(stringResource(R.string.perm_camera_title), stringResource(R.string.perm_camera_text))
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.perm_grant)) }
            } else {
                FlickerContent(vm)
            }
        }
    }
}

@Composable
private fun FlickerContent(vm: FlickerViewModel) {
    val st by vm.state.collectAsStateWithLifecycle()
    val cfg = st.config
    if (cfg == null) {
        InfoCard(stringResource(R.string.error_no_camera), "")
        return
    }

    Howto()

    // Kamera ko'rinishi (faqat yo'naltirish uchun). Sensor kadri landshaft — portret ekranda nisbat teskari.
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(cfg.previewSize.height.toFloat() / cfg.previewSize.width)
            .background(Color.Black, RoundedCornerShape(8.dp)),
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) {
                        t.setDefaultBufferSize(cfg.previewSize.width, cfg.previewSize.height)
                        vm.onSurfaceReady(Surface(t))
                    }
                    override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
                        vm.onSurfaceDestroyed()
                        return true
                    }
                    override fun onSurfaceTextureUpdated(t: SurfaceTexture) {}
                }
            }
        },
    )

    // Kamera haqida
    val mode = stringResource(if (cfg.manualSensor) R.string.camera_manual else R.string.camera_auto)
    Text(stringResource(R.string.camera_info, cfg.cameraId, cfg.yuvSize.width, cfg.yuvSize.height, mode) + " · " + cfg.hardwareLevelName,
        style = MaterialTheme.typography.bodySmall)
    Text(timingText(st.timing.tRowSec, st.timing.source), style = MaterialTheme.typography.bodySmall)
    st.settings?.let { s ->
        val level = ((st.live?.meanCode ?: 0.0) * 100).roundToInt()
        Text(stringResource(R.string.exposure_info, (1e9 / s.exposureNs).roundToInt(), s.iso, level), style = MaterialTheme.typography.bodySmall)
    }
    if (!cfg.hasSkew && st.calibratedTRowSec == null) WarningCard(stringResource(R.string.warn_no_skew))
    if (!cfg.manualSensor) WarningCard(stringResource(R.string.warn_no_manual))
    st.error?.let { WarningCard(stringResource(R.string.error_camera, it)) }

    when (st.mode) {
        FlickerMode.AIMING -> {
            LiveCard(st)
            Button(onClick = vm::startMeasurement, modifier = Modifier.fillMaxWidth(), enabled = st.live != null) {
                Text(stringResource(R.string.btn_measure))
            }
            CalibrationCard(st, vm)
        }
        FlickerMode.MEASURING, FlickerMode.CALIBRATING -> {
            val txt = when {
                st.mode == FlickerMode.CALIBRATING -> stringResource(R.string.calibrating, st.progress, st.progressTotal)
                st.passB -> stringResource(R.string.measuring_pass_b, st.progress, st.progressTotal)
                else -> stringResource(R.string.measuring, st.progress, st.progressTotal)
            }
            Text(txt)
            LinearProgressIndicator(progress = { st.progress.toFloat() / st.progressTotal.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
        }
        FlickerMode.RESULT -> {
            st.report?.let { ResultCard(it) }
            Button(onClick = vm::backToAiming, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_again)) }
        }
        FlickerMode.CALIBRATION_RESULT -> {
            CalibrationResultCard(st)
            Button(onClick = vm::backToAiming, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_done)) }
        }
    }

    InfoCard(stringResource(R.string.limits_title), stringResource(R.string.limits_text))
}

@Composable
private fun timingText(tRow: Double?, source: TimingSource): String = when {
    tRow == null -> stringResource(R.string.timing_unknown)
    source == TimingSource.CALIBRATED -> stringResource(R.string.timing_calibrated, tRow * 1e6)
    else -> stringResource(R.string.timing_skew, tRow * 1e6)
}

@Composable
private fun Howto() {
    var open by remember { mutableStateOf(true) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.howto_title), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) "▲" else "▼") }
            }
            if (open) {
                listOf(R.string.howto_1, R.string.howto_2, R.string.howto_3, R.string.howto_4, R.string.howto_5).forEach {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun LiveCard(st: FlickerUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.live_title), fontWeight = FontWeight.SemiBold)
            val live = st.live
            if (live == null) {
                Text(stringResource(R.string.live_waiting))
                return@Column
            }
            ProfileChart(live.profile)
            val fr = live.frame
            val msg = when {
                live.saturated > 0.003 -> stringResource(R.string.live_saturated)
                live.meanCode < 0.12 -> stringResource(R.string.live_too_dark)
                fr == null || !fr.detected -> stringResource(R.string.live_none)
                fr.frequencyHz != null -> stringResource(R.string.live_flicker, fmtHz(fr.frequencyHz!!), fr.modulationPct)
                else -> stringResource(R.string.live_flicker_nofreq, fr.modulationPct)
            }
            Text(msg, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ResultCard(r: FlickerReport) {
    val m = r.measurement
    val a = r.assessment
    val (color, title, desc) = when (a.level) {
        Ieee1789Level.NO_FLICKER_DETECTED -> Triple(StatusColors.ok, stringResource(R.string.ieee_no_flicker),
            stringResource(R.string.ieee_no_flicker_desc, m.detectionLimitPct))
        Ieee1789Level.NO_OBSERVABLE_EFFECT -> Triple(StatusColors.ok, stringResource(R.string.ieee_noel),
            stringResource(R.string.ieee_noel_desc, a.modulationPct ?: 0.0, limitText(a.noelLimitPct)))
        Ieee1789Level.LOW_RISK -> Triple(StatusColors.caution, stringResource(R.string.ieee_low),
            stringResource(R.string.ieee_low_desc, limitText(a.noelLimitPct), limitText(a.lowRiskLimitPct)))
        Ieee1789Level.HIGH_RISK -> Triple(StatusColors.bad, stringResource(R.string.ieee_high),
            stringResource(R.string.ieee_high_desc, a.modulationPct ?: 0.0, limitText(a.lowRiskLimitPct)))
        Ieee1789Level.FREQUENCY_UNKNOWN -> Triple(StatusColors.neutral, stringResource(R.string.ieee_unknown),
            stringResource(R.string.ieee_unknown_desc))
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.ieee_title), style = MaterialTheme.typography.labelLarge)
            Box(Modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Text(desc, style = MaterialTheme.typography.bodyMedium)
            if (a.borderline) Text(stringResource(R.string.ieee_borderline), color = StatusColors.caution, style = MaterialTheme.typography.bodyMedium)

            HorizontalDivider()
            ValueRow(stringResource(R.string.result_frequency), m.frequencyHz?.let { est(it, " Hz", ::fmtHz) })
            ValueRow(stringResource(R.string.result_modulation), m.modulationPct?.let { est(it, "%") { v -> fmt1(v) } })
            m.dutyCycle?.let { ValueRow(stringResource(R.string.result_duty), est(it, "%") { v -> fmt1(v * 100) }) }
            Text(stringResource(R.string.ci_note), style = MaterialTheme.typography.bodySmall)

            if (m.detected) {
                Ieee1789Chart(
                    fHz = m.frequencyHz?.mean, modPct = m.modulationPct?.mean,
                    fLow = m.frequencyHz?.low?.takeIf { !it.isNaN() }, fHigh = m.frequencyHz?.high?.takeIf { !it.isNaN() },
                    modLow = m.modulationPct?.low?.takeIf { !it.isNaN() }, modHigh = m.modulationPct?.high?.takeIf { !it.isNaN() },
                    labelNoel = stringResource(R.string.chart_noel), labelLow = stringResource(R.string.chart_low_risk),
                )
                m.waveform?.let { WaveformChart(it) }
                Text(stringResource(if (m.model == WaveformModel.RECTANGULAR_PWM) R.string.result_model_pwm else R.string.result_model_harmonic),
                    style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(R.string.result_limit, m.detectionLimitPct), style = MaterialTheme.typography.bodySmall)
            }

            Text(stringResource(R.string.result_frames, m.framesTotal, m.framesDetected, m.framesUsed), style = MaterialTheme.typography.bodySmall)
            val expInv = r.exposureSec?.let { (1.0 / it).roundToInt() } ?: 0
            Text(stringResource(R.string.result_conditions, expInv, r.iso ?: 0, r.rows, timingSourceText(m.timing.tRowSec, m.timing.source)),
                style = MaterialTheme.typography.bodySmall)
            if (r.fromPassB) Text(stringResource(R.string.result_pass_b, expInv), style = MaterialTheme.typography.bodySmall)

            if (m.warnings.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.warnings_title), fontWeight = FontWeight.SemiBold)
                m.warnings.forEach { Text("• " + warningText(it), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun timingSourceText(tRow: Double?, src: TimingSource) = timingText(tRow, src)

@Composable
private fun CalibrationCard(st: FlickerUiState, vm: FlickerViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.calib_title), fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.calib_howto), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.startCalibration(100.0) }, enabled = st.live != null) { Text(stringResource(R.string.calib_ref_100)) }
                OutlinedButton(onClick = { vm.startCalibration(120.0) }, enabled = st.live != null) { Text(stringResource(R.string.calib_ref_120)) }
            }
            st.calibratedTRowSec?.let { t ->
                Text(stringResource(R.string.timing_calibrated, t * 1e6), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.calib_use), modifier = Modifier.weight(1f))
                    Switch(checked = st.useCalibration, onCheckedChange = vm::setUseCalibration)
                }
                TextButton(onClick = vm::clearCalibration) { Text(stringResource(R.string.calib_clear)) }
            }
        }
    }
}

@Composable
private fun CalibrationResultCard(st: FlickerUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.calib_title), fontWeight = FontWeight.SemiBold)
            val c = st.calibration
            if (c == null || st.calibrationFailed) {
                Text(stringResource(R.string.calib_failed), color = StatusColors.bad)
            } else {
                Text(stringResource(R.string.calib_result, c.tRow.mean * 1e6, (c.tRow.ci95.takeIf { !it.isNaN() } ?: 0.0) * 1e6, c.framesUsed))
                c.deviationFromSkewPct?.let { Text(stringResource(R.string.calib_deviation, it)) }
                Text(stringResource(R.string.calib_saved), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value ?: stringResource(R.string.result_not_measured), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InfoCard(title: String, text: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (text.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun WarningCard(text: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = StatusColors.caution.copy(alpha = 0.15f))) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun warningText(w: FlickerWarning): String = stringResource(
    when (w) {
        FlickerWarning.NO_TIMING -> R.string.w_no_timing
        FlickerWarning.NO_EXPOSURE -> R.string.w_no_exposure
        FlickerWarning.LOW_SINC -> R.string.w_low_sinc
        FlickerWarning.STATIC_PHASE -> R.string.w_static_phase
        FlickerWarning.INCONSISTENT_FREQUENCY -> R.string.w_inconsistent
        FlickerWarning.FEW_CYCLES -> R.string.w_few_cycles
        FlickerWarning.SATURATED -> R.string.w_saturated
        FlickerWarning.TOO_DARK -> R.string.w_too_dark
        FlickerWarning.NONLINEAR_RESPONSE -> R.string.w_nonlinear
        FlickerWarning.FEW_FRAMES -> R.string.w_few_frames
    },
)

@Composable
private fun limitText(v: Double?): String =
    if (v == null || v.isInfinite()) stringResource(R.string.no_limit) else fmt1(v) + "%"

private fun fmt1(v: Double) = String.format(Locale.ROOT, "%.1f", v)

private fun fmtHz(v: Double): String = when {
    v >= 1000 -> String.format(Locale.ROOT, "%.0f", v)
    v >= 100 -> String.format(Locale.ROOT, "%.1f", v)
    else -> String.format(Locale.ROOT, "%.2f", v)
}

/** "qiymat ± CI birlik" ko'rinishida; CI hisoblanmagan bo'lsa faqat qiymat. */
private fun est(e: Estimate, unit: String, f: (Double) -> String): String =
    if (e.ci95.isNaN()) f(e.mean) + unit else f(e.mean) + " ± " + f(e.ci95) + unit
