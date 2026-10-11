package uz.monitorcheck.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import uz.monitorcheck.analysis.screen.BitDepthVerdict
import uz.monitorcheck.analysis.screen.DefectKind
import uz.monitorcheck.analysis.screen.QualityLevel
import uz.monitorcheck.analysis.screen.TestPattern
import uz.monitorcheck.data.Resolution
import uz.monitorcheck.data.ScreenTestResult
import uz.monitorcheck.data.ScreenWarning
import uz.monitorcheck.data.SessionRepository
import uz.monitorcheck.ui.components.AppScaffold
import uz.monitorcheck.ui.components.BottomActionBar
import uz.monitorcheck.ui.components.ExpandableCard
import uz.monitorcheck.ui.components.KeyValue
import uz.monitorcheck.ui.components.NoticeCard
import uz.monitorcheck.ui.components.NoticeKind
import uz.monitorcheck.ui.components.PrimaryButton
import uz.monitorcheck.ui.components.SectionCard
import uz.monitorcheck.ui.components.StatChip
import uz.monitorcheck.ui.components.StepList
import uz.monitorcheck.ui.components.VerdictBanner
import uz.monitorcheck.ui.theme.StatusColors

/** Bu modulda suratga olinadigan testlar. */
val SCREEN_TESTS = listOf(
    TestPattern.WHITE, TestPattern.BLACK, TestPattern.RED, TestPattern.GREEN, TestPattern.BLUE,
    TestPattern.GRAY50, TestPattern.BLEED, TestPattern.GRADIENT_STEPS,
)

@StringRes
fun patternName(p: TestPattern): Int = when (p) {
    TestPattern.WHITE -> R.string.p_white
    TestPattern.BLACK -> R.string.p_black
    TestPattern.RED -> R.string.p_red
    TestPattern.GREEN -> R.string.p_green
    TestPattern.BLUE -> R.string.p_blue
    TestPattern.GRAY50 -> R.string.p_gray
    TestPattern.BLEED -> R.string.p_bleed
    TestPattern.GRADIENT -> R.string.p_gradient
    TestPattern.GRADIENT_STEPS -> R.string.p_steps
    TestPattern.FLICKER -> R.string.p_flicker
}

@StringRes
private fun patternPurpose(p: TestPattern): Int = when (p) {
    TestPattern.WHITE -> R.string.pp_white
    TestPattern.BLACK -> R.string.pp_black
    TestPattern.RED, TestPattern.GREEN, TestPattern.BLUE -> R.string.pp_color
    TestPattern.GRAY50 -> R.string.pp_gray
    TestPattern.BLEED -> R.string.pp_bleed
    TestPattern.GRADIENT_STEPS -> R.string.pp_steps
    else -> R.string.pp_other
}

@Composable
fun ScreenTestScreen(onBack: () -> Unit, vm: ScreenTestViewModel = viewModel()) {
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
    val results by SessionRepository.screen.collectAsStateWithLifecycle()
    val resolution by SessionRepository.resolution.collectAsStateWithLifecycle()

    AppScaffold(
        title = stringResource(R.string.s_title),
        onBack = onBack,
        backLabel = stringResource(R.string.back),
        bottomBar = {
            if (granted && st.config != null) BottomActionBar {
                when (st.phase) {
                    ScreenPhase.CAPTURING -> {
                        Text(stringResource(R.string.s_capturing), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    ScreenPhase.ANALYZING -> {
                        Text(stringResource(R.string.s_analyzing), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    ScreenPhase.AIMING -> PrimaryButton(stringResource(R.string.s_btn_capture), vm::capture, enabled = st.live?.ready == true)
                }
            }
        },
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

        ExpandableCard(stringResource(R.string.s_howto_title), initiallyExpanded = results.isEmpty()) {
            StepList(listOf(
                stringResource(R.string.s_step1), stringResource(R.string.s_step2), stringResource(R.string.s_step3),
                stringResource(R.string.s_step4), stringResource(R.string.s_step5),
            ))
        }

        // Kamera ko'rinishi + aniqlangan ekran konturi
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
            val live = st.live
            if (live != null) OutlineOverlay(live.outline, live.ready, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.TopStart).padding(10.dp)) {
                val (txt, col) = when {
                    live == null || live.markersFound == 0 -> stringResource(R.string.s_status_searching) to Color(0xCC111827)
                    live.ready && live.pattern != null -> stringResource(R.string.s_status_ready, live.pattern.id, stringResource(patternName(live.pattern))) to StatusColors.ok
                    else -> stringResource(R.string.s_status_partial, live.markersFound) to StatusColors.caution
                }
                StatChip(txt, color = col, textColor = Color.White)
            }
        }

        st.error?.let { e ->
            val msg = when (e) {
                ScreenError.NO_GEOMETRY -> stringResource(R.string.se_no_geometry)
                ScreenError.NOT_ANALYZABLE -> stringResource(R.string.se_not_analyzable)
                ScreenError.MEMORY -> stringResource(R.string.se_memory)
                ScreenError.CAMERA -> stringResource(R.string.se_camera, st.errorDetail ?: "")
            }
            NoticeCard(msg, NoticeKind.ERROR)
        }

        // Natijalar ro'yxati
        SectionCard(stringResource(R.string.s_tests_title), stringResource(R.string.s_tests_subtitle)) {
            SCREEN_TESTS.forEach { p -> TestRow(p, results[p], selected = st.selected == p) { vm.select(p) } }
        }

        st.selected?.let { p -> results[p]?.let { ResultDetail(it) } }

        Summary(results, resolution)

        ExpandableCard(stringResource(R.string.s_settings)) {
            Text(stringResource(R.string.s_resolution), fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Resolution.COMMON.forEach { r ->
                    FilterChip(selected = r == resolution, onClick = { vm.setResolution(r) }, label = { Text(r.toString()) })
                }
            }
            Text(stringResource(R.string.s_resolution_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            if (cfg.manualFocus) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.s_defocus), modifier = Modifier.weight(1f))
                    Switch(checked = st.defocus, onCheckedChange = vm::setDefocus)
                }
            } else {
                Text(stringResource(R.string.s_defocus_na), style = MaterialTheme.typography.bodySmall)
            }
        }

        ExpandableCard(stringResource(R.string.limits_title)) {
            Text(stringResource(R.string.s_limits_text), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.size(8.dp))
    }
}

private fun levelColor(l: QualityLevel) = when (l) {
    QualityLevel.GOOD -> StatusColors.ok
    QualityLevel.FAIR -> StatusColors.caution
    QualityLevel.POOR -> StatusColors.bad
}

@StringRes
private fun levelName(l: QualityLevel) = when (l) {
    QualityLevel.GOOD -> R.string.q_good
    QualityLevel.FAIR -> R.string.q_fair
    QualityLevel.POOR -> R.string.q_poor
}

/** Test natijasining qisqa holati: (rang, matn). */
@Composable
private fun shortStatus(r: ScreenTestResult): Pair<Color, String> {
    r.uniformity?.let { u ->
        val pixel = r.defects?.defects?.count { it.kind != DefectKind.SPOT } ?: 0
        val col = if (pixel > 0) StatusColors.bad else levelColor(u.level)
        return col to stringResource(R.string.s_short_uniform, u.uniformityPct, pixel)
    }
    r.defects?.let { d ->
        val n = d.defects.count { it.kind != DefectKind.SPOT }
        return (if (n == 0) StatusColors.ok else StatusColors.bad) to
            (if (n == 0) stringResource(R.string.s_defects_none) else stringResource(R.string.s_short_defects, n))
    }
    r.bleed?.let { b -> return levelColor(b.level) to stringResource(R.string.s_short_bleed, b.maxEdgeRatio) }
    r.banding?.let { b ->
        val col = when (b.verdict) {
            BitDepthVerdict.EIGHT_BIT_OR_FRC -> StatusColors.ok
            BitDepthVerdict.SIX_BIT -> StatusColors.bad
            BitDepthVerdict.INCONCLUSIVE -> StatusColors.neutral
        }
        return col to stringResource(R.string.s_short_steps, b.bright.visibleSteps)
    }
    return StatusColors.neutral to ""
}

@Composable
private fun TestRow(p: TestPattern, r: ScreenTestResult?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(enabled = r != null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Text("${p.id}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(patternName(p)), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(patternPurpose(p)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (r == null) {
            Text(stringResource(R.string.s_not_done), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        } else {
            val (c, t) = shortStatus(r)
            StatChip(t, color = c, textColor = Color.White)
        }
    }
}

@Composable
private fun ResultDetail(r: ScreenTestResult) {
    SectionCard(stringResource(R.string.s_result_title, stringResource(patternName(r.pattern)))) {
        r.uniformity?.let { u ->
            Text(stringResource(R.string.s_uniformity), fontWeight = FontWeight.SemiBold)
            UniformityGrid(u.relativeToCenterPct)
            KeyValue(stringResource(R.string.s_uniformity_u), "%.0f%%".format(u.uniformityPct))
            KeyValue(stringResource(R.string.s_uniformity_dev), "%.0f%%".format(u.maxDeviationFromCenterPct))
            KeyValue(stringResource(R.string.s_level), stringResource(levelName(u.level)))
            HorizontalDivider()
        }
        r.defects?.let { d ->
            Text(stringResource(R.string.s_defects), fontWeight = FontWeight.SemiBold)
            DefectMap(d.defects)
            val pts = d.defects.filter { it.kind != DefectKind.SPOT }
            val spots = d.defects.count { it.kind == DefectKind.SPOT }
            Text(
                if (d.defects.isEmpty()) stringResource(R.string.s_defects_none) else stringResource(R.string.s_defects_count, pts.size, spots),
                style = MaterialTheme.typography.bodyMedium,
            )
            d.defects.take(12).forEach { def ->
                val kind = stringResource(
                    when (def.kind) {
                        DefectKind.DARK_PIXEL -> R.string.d_dark
                        DefectKind.BRIGHT_PIXEL -> R.string.d_bright
                        DefectKind.SUBPIXEL -> R.string.d_sub
                        DefectKind.SPOT -> R.string.d_spot
                    },
                )
                Text("• " + stringResource(R.string.s_defect_item, kind, def.u * 100, def.v * 100), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.s_scale, d.cameraPxPerScreenPx), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        r.bleed?.let { b ->
            Text(stringResource(R.string.s_bleed), fontWeight = FontWeight.SemiBold)
            BleedHeatmap(b.map, b.cols, b.rows)
            KeyValue(stringResource(R.string.s_bleed_ratio), "%.1f".format(b.maxEdgeRatio))
            KeyValue(stringResource(R.string.s_level), stringResource(levelName(b.level)))
        }
        r.banding?.let { b ->
            Text(stringResource(R.string.s_banding), fontWeight = FontWeight.SemiBold)
            BandingChart(b.dark.means, b.bright.means)
            KeyValue(stringResource(R.string.s_band_bright), "${b.bright.visibleSteps} / 64")
            KeyValue(stringResource(R.string.s_band_dark), "${b.dark.visibleSteps} / 64")
            val v = when (b.verdict) {
                BitDepthVerdict.EIGHT_BIT_OR_FRC -> R.string.v_8bit
                BitDepthVerdict.SIX_BIT -> R.string.v_6bit
                BitDepthVerdict.INCONCLUSIVE -> R.string.v_inconclusive
            }
            Text(stringResource(v), fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.s_frc_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        r.maxViewAngleDeg?.let { Text(stringResource(R.string.s_view_angle, it), style = MaterialTheme.typography.bodySmall) }
        r.warnings.forEach { w ->
            NoticeCard(stringResource(when (w) {
                ScreenWarning.MOIRE -> R.string.sw_moire
                ScreenWarning.LOW_RESOLUTION -> R.string.sw_lowres
                ScreenWarning.SATURATED -> R.string.sw_saturated
                ScreenWarning.WIDE_ANGLE -> R.string.sw_wide
            }), NoticeKind.WARNING)
        }
    }
}

@Composable
private fun Summary(results: Map<TestPattern, ScreenTestResult>, res: Resolution) {
    val px = SessionRepository.pixelSummary(results, res) ?: return
    val cls = stringResource(when (px.isoClass) {
        0 -> R.string.iso_0
        1 -> R.string.iso_1
        2 -> R.string.iso_2
        3 -> R.string.iso_3
        else -> R.string.iso_4
    })
    val color = when (px.isoClass) { 0 -> StatusColors.ok; 1 -> StatusColors.caution; else -> StatusColors.bad }
    VerdictBanner(
        color = color,
        caption = stringResource(R.string.s_summary),
        title = stringResource(R.string.s_iso_class, cls),
        subtitle = stringResource(R.string.s_summary_counts, px.type1, px.type2, px.type3) + "\n" +
            stringResource(R.string.s_summary_tests, px.testsDone, res.toString()),
    )
}
