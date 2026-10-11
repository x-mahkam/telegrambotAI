package uz.monitorcheck.ui.flicker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
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
import uz.monitorcheck.data.FlickerReport
import uz.monitorcheck.ui.components.AppScaffold
import uz.monitorcheck.ui.components.BottomActionBar
import uz.monitorcheck.ui.components.ExpandableCard
import uz.monitorcheck.ui.components.KeyValue
import uz.monitorcheck.ui.components.MetricTile
import uz.monitorcheck.ui.components.NoticeCard
import uz.monitorcheck.ui.components.NoticeKind
import uz.monitorcheck.ui.components.PrimaryButton
import uz.monitorcheck.ui.components.SectionCard
import uz.monitorcheck.ui.components.StatChip
import uz.monitorcheck.ui.components.StepList
import uz.monitorcheck.ui.components.VerdictBanner
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
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val st by vm.state.collectAsStateWithLifecycle()

    AppScaffold(
        title = stringResource(R.string.flicker_title),
        onBack = onBack,
        backLabel = stringResource(R.string.back),
        bottomBar = { if (granted && st.config != null) ActionBar(st, vm) },
    ) {
        if (!granted) {
            SectionCard(stringResource(R.string.perm_camera_title), stringResource(R.string.perm_camera_text)) {
                PrimaryButton(stringResource(R.string.perm_grant), { launcher.launch(Manifest.permission.CAMERA) })
            }
            return@AppScaffold
        }
        val cfg = st.config
        if (cfg == null) {
            NoticeCard(stringResource(R.string.error_no_camera), NoticeKind.ERROR)
            return@AppScaffold
        }

        if (st.mode == FlickerMode.RESULT) st.report?.let { ResultSection(it) }
        if (st.mode == FlickerMode.CALIBRATION_RESULT) CalibrationResultCard(st)

        ExpandableCard(stringResource(R.string.howto_title), initiallyExpanded = st.report == null) {
            StepList(listOf(
                stringResource(R.string.howto_1), stringResource(R.string.howto_2), stringResource(R.string.howto_3),
                stringResource(R.string.howto_4), stringResource(R.string.howto_5),
            ))
        }

        // Kamera ko'rinishi + holat tabletkasi
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(cfg.previewSize.height.toFloat() / cfg.previewSize.width)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) {
                                t.setDefaultBufferSize(cfg.previewSize.width, cfg.previewSize.height)
                                vm.onSurfaceReady(Surface(t))
                            }
                            override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {}
                            override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean { vm.onSurfaceDestroyed(); return true }
                            override fun onSurfaceTextureUpdated(t: SurfaceTexture) {}
                        }
                    }
                },
            )
            Box(Modifier.align(Alignment.TopStart).padding(10.dp)) {
                val (txt, col) = when (st.control) {
                    ExposureControl.PROBING -> stringResource(R.string.ctl_probing) to Color(0xCC111827)
                    ExposureControl.MANUAL -> stringResource(R.string.ctl_manual) to StatusColors.ok
                    ExposureControl.AUTO -> stringResource(R.string.ctl_auto) to StatusColors.caution
                }
                StatChip(txt, color = col, textColor = Color.White)
            }
        }

        // Haqiqiy kamera parametrlari
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            st.actualExposureNs?.let { StatChip(stringResource(R.string.chip_exposure, (1e9 / it).roundToInt())) }
            st.actualIso?.let { StatChip(stringResource(R.string.chip_iso, it)) }
            StatChip(timingText(st.timing.tRowSec, st.timing.source))
            st.live?.let { StatChip(stringResource(R.string.chip_level, (it.meanCode * 100).roundToInt())) }
        }

        if (st.control == ExposureControl.AUTO) NoticeCard(stringResource(R.string.warn_auto_exposure), NoticeKind.WARNING)
        if (!cfg.hasSkew && st.calibratedTRowSec == null) NoticeCard(stringResource(R.string.warn_no_skew), NoticeKind.WARNING)
        st.error?.let { NoticeCard(stringResource(R.string.error_camera, it), NoticeKind.ERROR) }

        if (st.mode == FlickerMode.AIMING) LiveCard(st)

        CalibrationCard(st, vm)

        ExpandableCard(stringResource(R.string.limits_title)) {
            Text(stringResource(R.string.limits_text), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            stringResource(R.string.camera_info, cfg.cameraId, cfg.yuvSize.width, cfg.yuvSize.height, cfg.hardwareLevelName),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.size(8.dp))
    }
}

@Composable
private fun ActionBar(st: FlickerUiState, vm: FlickerViewModel) {
    BottomActionBar {
        when (st.mode) {
            FlickerMode.MEASURING, FlickerMode.CALIBRATING -> {
                val txt = when {
                    st.mode == FlickerMode.CALIBRATING -> stringResource(R.string.calibrating, st.progress, st.progressTotal)
                    st.passB -> stringResource(R.string.measuring_pass_b, st.progress, st.progressTotal)
                    else -> stringResource(R.string.measuring, st.progress, st.progressTotal)
                }
                Text(txt, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { st.progress.toFloat() / st.progressTotal.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            }
            FlickerMode.RESULT, FlickerMode.CALIBRATION_RESULT ->
                PrimaryButton(stringResource(R.string.btn_again), vm::backToAiming)
            FlickerMode.AIMING ->
                PrimaryButton(
                    if (st.control == ExposureControl.PROBING) stringResource(R.string.ctl_probing) else stringResource(R.string.btn_measure),
                    vm::startMeasurement,
                    enabled = st.live != null && st.control != ExposureControl.PROBING,
                )
        }
    }
}

@Composable
private fun timingText(tRow: Double?, source: TimingSource): String = when {
    tRow == null -> stringResource(R.string.timing_unknown)
    source == TimingSource.CALIBRATED -> stringResource(R.string.timing_calibrated, tRow * 1e6)
    else -> stringResource(R.string.timing_skew, tRow * 1e6)
}

@Composable
private fun LiveCard(st: FlickerUiState) {
    SectionCard(stringResource(R.string.live_title)) {
        val live = st.live
        if (live == null) {
            Text(stringResource(R.string.live_waiting), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
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
        Text(msg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ResultSection(r: FlickerReport) {
    val m = r.measurement
    val a = r.assessment
    val cov = a.coverageHz?.let { fmtHz(it) } ?: "—"
    val (color, title, desc) = when (a.level) {
        Ieee1789Level.NO_FLICKER_DETECTED -> Triple(StatusColors.ok, stringResource(R.string.ieee_no_flicker),
            stringResource(R.string.ieee_no_flicker_desc, m.detectionLimitPct, cov))
        Ieee1789Level.INCONCLUSIVE -> Triple(StatusColors.neutral, stringResource(R.string.ieee_inconclusive),
            stringResource(R.string.ieee_inconclusive_desc, cov))
        Ieee1789Level.NO_OBSERVABLE_EFFECT -> Triple(StatusColors.ok, stringResource(R.string.ieee_noel),
            stringResource(R.string.ieee_noel_desc, a.modulationPct ?: 0.0, limitText(a.noelLimitPct)))
        Ieee1789Level.LOW_RISK -> Triple(StatusColors.caution, stringResource(R.string.ieee_low),
            stringResource(R.string.ieee_low_desc, limitText(a.noelLimitPct), limitText(a.lowRiskLimitPct)))
        Ieee1789Level.HIGH_RISK -> Triple(StatusColors.bad, stringResource(R.string.ieee_high),
            stringResource(R.string.ieee_high_desc, a.modulationPct ?: 0.0, limitText(a.lowRiskLimitPct)))
        Ieee1789Level.FREQUENCY_UNKNOWN -> Triple(StatusColors.neutral, stringResource(R.string.ieee_unknown),
            stringResource(R.string.ieee_unknown_desc))
    }
    VerdictBanner(color, title, desc, caption = stringResource(R.string.ieee_title))
    if (a.borderline) NoticeCard(stringResource(R.string.ieee_borderline), NoticeKind.WARNING)

    if (m.detected) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(stringResource(R.string.result_frequency), m.frequencyHz?.let { fmtHz(it.mean) + " Hz" } ?: "—",
                m.frequencyHz?.let { ciText(it, " Hz", ::fmtHz) })
            MetricTile(stringResource(R.string.result_modulation), m.modulationPct?.let { fmt1(it.mean) + "%" } ?: "—",
                m.modulationPct?.let { ciText(it, "%") { v -> fmt1(v) } })
        }
        m.dutyCycle?.let { d ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(stringResource(R.string.result_duty), fmt1(d.mean * 100) + "%", ciText(d, "%") { v -> fmt1(v * 100) })
                MetricTile(stringResource(R.string.result_model), stringResource(
                    if (m.model == WaveformModel.RECTANGULAR_PWM) R.string.result_model_pwm else R.string.result_model_harmonic))
            }
        }
        SectionCard(stringResource(R.string.ieee_chart_title)) {
            Ieee1789Chart(
                fHz = m.frequencyHz?.mean, modPct = m.modulationPct?.mean,
                fLow = m.frequencyHz?.low?.takeIf { !it.isNaN() }, fHigh = m.frequencyHz?.high?.takeIf { !it.isNaN() },
                modLow = m.modulationPct?.low?.takeIf { !it.isNaN() }, modHigh = m.modulationPct?.high?.takeIf { !it.isNaN() },
                labelNoel = stringResource(R.string.chart_noel), labelLow = stringResource(R.string.chart_low_risk),
            )
            m.waveform?.let {
                Text(stringResource(R.string.waveform_title), style = MaterialTheme.typography.labelLarge)
                WaveformChart(it)
            }
            Text(stringResource(R.string.ci_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    ExpandableCard(stringResource(R.string.details_title)) {
        val expInv = r.exposureSec?.let { (1.0 / it).roundToInt() } ?: 0
        KeyValue(stringResource(R.string.d_frames), stringResource(R.string.d_frames_value, m.framesTotal, m.framesDetected, m.framesUsed))
        KeyValue(stringResource(R.string.d_exposure), if (expInv > 0) "1/$expInv s" else "—")
        KeyValue(stringResource(R.string.d_mode), stringResource(if (r.manualExposure) R.string.ctl_manual else R.string.ctl_auto))
        KeyValue(stringResource(R.string.d_iso), r.iso?.toString() ?: "—")
        KeyValue(stringResource(R.string.d_rows), r.rows.toString())
        KeyValue(stringResource(R.string.d_trow), timingText(m.timing.tRowSec, m.timing.source))
        KeyValue(stringResource(R.string.d_coverage), a.coverageHz?.let { "≤ ${fmtHz(it)} Hz" } ?: "—")
        KeyValue(stringResource(R.string.d_limit), "~${fmt1(m.detectionLimitPct)}%")
        if (r.fromPassB) Text(stringResource(R.string.result_pass_b), style = MaterialTheme.typography.bodySmall)
    }
    m.warnings.forEach { NoticeCard(warningText(it), NoticeKind.WARNING) }
}

@Composable
private fun CalibrationCard(st: FlickerUiState, vm: FlickerViewModel) {
    ExpandableCard(stringResource(R.string.calib_title)) {
        Text(stringResource(R.string.calib_howto), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val enabled = st.live != null && st.mode == FlickerMode.AIMING && st.control != ExposureControl.PROBING
            OutlinedButton(onClick = { vm.startCalibration(100.0) }, enabled = enabled) { Text(stringResource(R.string.calib_ref_100)) }
            OutlinedButton(onClick = { vm.startCalibration(120.0) }, enabled = enabled) { Text(stringResource(R.string.calib_ref_120)) }
        }
        st.calibratedTRowSec?.let { t ->
            HorizontalDivider()
            Text(stringResource(R.string.timing_calibrated, t * 1e6), fontWeight = FontWeight.SemiBold)
            st.skewTRowSec?.let { s -> Text(stringResource(R.string.calib_deviation, 100 * (t - s) / s), style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.calib_use), modifier = Modifier.weight(1f))
                Switch(checked = st.useCalibration, onCheckedChange = vm::setUseCalibration)
            }
            TextButton(onClick = vm::clearCalibration) { Text(stringResource(R.string.calib_clear)) }
        }
    }
}

@Composable
private fun CalibrationResultCard(st: FlickerUiState) {
    val c = st.calibration
    if (c == null || st.calibrationFailed) {
        VerdictBanner(StatusColors.bad, stringResource(R.string.calib_title), stringResource(R.string.calib_failed))
    } else {
        val dev = c.deviationFromSkewPct?.let { "\n" + stringResource(R.string.calib_deviation, it) } ?: ""
        VerdictBanner(
            StatusColors.ok,
            stringResource(R.string.calib_result, c.tRow.mean * 1e6, (c.tRow.ci95.takeIf { !it.isNaN() } ?: 0.0) * 1e6, c.framesUsed),
            stringResource(R.string.calib_saved) + dev,
            caption = stringResource(R.string.calib_title),
        )
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

/** "± CI birlik" (CI hisoblanmagan bo'lsa — null). */
private fun ciText(e: Estimate, unit: String, f: (Double) -> String): String? =
    if (e.ci95.isNaN()) null else "± " + f(e.ci95) + unit
