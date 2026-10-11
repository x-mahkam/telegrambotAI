package uz.monitorcheck.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uz.monitorcheck.R

@Composable
fun HomeScreen(onOpenFlicker: () -> Unit) {
    Scaffold { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            ModuleCard(R.string.module_flicker, R.string.module_flicker_desc, enabled = true, onClick = onOpenFlicker)
            ModuleCard(R.string.module_pixels, R.string.module_pixels_desc, enabled = false)
            ModuleCard(R.string.module_edid, R.string.module_edid_desc, enabled = false)
            ModuleCard(R.string.module_report, R.string.module_report_desc, enabled = false)
            ModuleCard(R.string.module_spectrum, R.string.module_spectrum_desc, enabled = false)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.disclaimer), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ModuleCard(@StringRes title: Int, @StringRes desc: Int, enabled: Boolean, onClick: () -> Unit = {}) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(desc), style = MaterialTheme.typography.bodyMedium)
            if (!enabled) {
                Text(stringResource(R.string.coming_soon), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
