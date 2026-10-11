package uz.monitorcheck.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Natija holatlari ranglari (IEEE 1789 zonalari, xulosalar). */
object StatusColors {
    val ok = Color(0xFF15803D)
    val caution = Color(0xFFB45309)
    val bad = Color(0xFFB91C1C)
    val neutral = Color(0xFF475569)
    val info = Color(0xFF1D4ED8)
}

/** Brend gradienti (bosh ekran sarlavhasi). */
object Brand {
    val start = Color(0xFF1E3A8A)
    val end = Color(0xFF0E7490)
}

private val Light = lightColorScheme(
    primary = Color(0xFF2457D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF0B2A6F),
    secondary = Color(0xFF0E7490),
    secondaryContainer = Color(0xFFCFF3F9),
    tertiary = Color(0xFF6D28D9),
    background = Color(0xFFF4F6FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDF1F7),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFE2E8F0),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF0B2A6F),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFF67E8F9),
    secondaryContainer = Color(0xFF164E63),
    tertiary = Color(0xFFC4B5FD),
    background = Color(0xFF0B1220),
    surface = Color(0xFF111A2E),
    surfaceVariant = Color(0xFF1B263D),
    onSurfaceVariant = Color(0xFFB6C2D6),
    outline = Color(0xFF52627D),
    outlineVariant = Color(0xFF26324A),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
)

@Composable
fun MonitorCheckTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = AppShapes,
        content = content,
    )
}
