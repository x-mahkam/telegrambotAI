package uz.monitorcheck.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.monitorcheck.R
import uz.monitorcheck.analysis.standards.Ieee1789Level
import uz.monitorcheck.data.SessionRepository
import uz.monitorcheck.ui.components.ModuleIcon
import uz.monitorcheck.ui.components.ModuleKind
import uz.monitorcheck.ui.components.SectionCard
import uz.monitorcheck.ui.components.StatChip
import uz.monitorcheck.ui.components.StatusLine
import uz.monitorcheck.ui.theme.Brand
import uz.monitorcheck.ui.theme.StatusColors

@Composable
fun HomeScreen(onOpenFlicker: () -> Unit, onOpenScreen: () -> Unit) {
    val flicker by SessionRepository.flicker.collectAsStateWithLifecycle()
    val screen by SessionRepository.screen.collectAsStateWithLifecycle()
    val resolution by SessionRepository.resolution.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        // Sarlavha
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(Brand.start, Brand.end)))
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.app_name), color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.home_title), color = Color.White.copy(alpha = 0.92f), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.home_subtitle), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatChip(stringResource(R.string.chip_offline), color = Color.White.copy(alpha = 0.18f), textColor = Color.White)
                    StatChip(stringResource(R.string.chip_standards), color = Color.White.copy(alpha = 0.18f), textColor = Color.White)
                }
            }
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.home_modules), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModuleCard(ModuleKind.FLICKER, R.string.module_flicker, R.string.module_flicker_desc, true, onOpenFlicker, Modifier.weight(1f))
                ModuleCard(ModuleKind.PIXELS, R.string.module_pixels, R.string.module_pixels_desc, true, onOpenScreen, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModuleCard(ModuleKind.EDID, R.string.module_edid, R.string.module_edid_desc, false, {}, Modifier.weight(1f))
                ModuleCard(ModuleKind.REPORT, R.string.module_report, R.string.module_report_desc, false, {}, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModuleCard(ModuleKind.SPECTRUM, R.string.module_spectrum, R.string.module_spectrum_desc, false, {}, Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }

            // Joriy sessiya
            SectionCard(stringResource(R.string.session_title), stringResource(R.string.session_subtitle)) {
                val f = flicker
                if (f == null) {
                    StatusLine(StatusColors.neutral, stringResource(R.string.session_flicker_none))
                } else {
                    val (c, t) = when (f.assessment.level) {
                        Ieee1789Level.NO_FLICKER_DETECTED -> StatusColors.ok to stringResource(R.string.ieee_no_flicker)
                        Ieee1789Level.NO_OBSERVABLE_EFFECT -> StatusColors.ok to stringResource(R.string.ieee_noel)
                        Ieee1789Level.LOW_RISK -> StatusColors.caution to stringResource(R.string.ieee_low)
                        Ieee1789Level.HIGH_RISK -> StatusColors.bad to stringResource(R.string.ieee_high)
                        Ieee1789Level.INCONCLUSIVE -> StatusColors.neutral to stringResource(R.string.ieee_inconclusive)
                        Ieee1789Level.FREQUENCY_UNKNOWN -> StatusColors.neutral to stringResource(R.string.ieee_unknown)
                    }
                    StatusLine(c, stringResource(R.string.session_flicker, t))
                }
                val px = SessionRepository.pixelSummary(screen, resolution)
                if (px == null) {
                    StatusLine(StatusColors.neutral, stringResource(R.string.session_pixels_none))
                } else {
                    val c = when (px.isoClass) { 0 -> StatusColors.ok; 1 -> StatusColors.caution; else -> StatusColors.bad }
                    StatusLine(c, stringResource(R.string.session_pixels, screen.size, px.type1 + px.type2 + px.type3))
                }
            }

            Text(stringResource(R.string.disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ModuleCard(kind: ModuleKind, @StringRes title: Int, @StringRes desc: Int, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Card(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.6f)
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (enabled) 2.dp else 0.dp),
    ) {
        Column(Modifier.padding(14.dp).height(150.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { ModuleIcon(kind, MaterialTheme.colorScheme.primary, Modifier.size(26.dp)) }
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                stringResource(if (enabled) R.string.module_open else R.string.coming_soon),
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
