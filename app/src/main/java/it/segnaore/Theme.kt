package it.segnaore

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Colori delle categorie di ore, uguali in tutta l'app. */
object Palette {
    val Ordinary = Color(0xFF2F6FED)   // blu
    val Overtime = Color(0xFFFF7A2F)   // arancio
    val Holiday = Color(0xFFE5484D)    // rosso
    val Travel = Color(0xFF12B886)     // verde acqua
    val Days = Color(0xFF7C5CFF)       // viola
    val Worked = Color(0xFF1D4ED8)     // blu scuro

    val heroLight = Brush.linearGradient(listOf(Color(0xFF2F6FED), Color(0xFF7C5CFF)))
    val heroDark = Brush.linearGradient(listOf(Color(0xFF1E3A8A), Color(0xFF4C1D95)))
}

private val Light = lightColorScheme(
    primary = Color(0xFF2F6FED),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE7FF),
    onPrimaryContainer = Color(0xFF0B2A6B),
    secondary = Color(0xFFFF7A2F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE4D3),
    onSecondaryContainer = Color(0xFF5A2300),
    tertiary = Color(0xFF12B886),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFF5E7),
    onTertiaryContainer = Color(0xFF003828),
    background = Color(0xFFF3F5FC),
    onBackground = Color(0xFF151A2D),
    surface = Color(0xFFF3F5FC),
    onSurface = Color(0xFF151A2D),
    surfaceVariant = Color(0xFFE6EBF8),
    onSurfaceVariant = Color(0xFF586179),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F9FE),
    surfaceContainer = Color(0xFFF0F3FC),
    surfaceContainerHigh = Color(0xFFE9EDFA),
    surfaceContainerHighest = Color(0xFFE2E8F8),
    outline = Color(0xFFC3CADB),
    outlineVariant = Color(0xFFDDE2EE),
    error = Color(0xFFD92D20),
    errorContainer = Color(0xFFFFE1DE),
    onErrorContainer = Color(0xFF5F0B05)
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FB2FF),
    onPrimary = Color(0xFF0B2A6B),
    primaryContainer = Color(0xFF213E86),
    onPrimaryContainer = Color(0xFFDDE7FF),
    secondary = Color(0xFFFFA36E),
    onSecondary = Color(0xFF4A1C00),
    secondaryContainer = Color(0xFF6B3008),
    onSecondaryContainer = Color(0xFFFFE4D3),
    tertiary = Color(0xFF5FE0B4),
    onTertiary = Color(0xFF003828),
    tertiaryContainer = Color(0xFF0B5A43),
    onTertiaryContainer = Color(0xFFCFF5E7),
    background = Color(0xFF0E1120),
    onBackground = Color(0xFFE6E9F5),
    surface = Color(0xFF0E1120),
    onSurface = Color(0xFFE6E9F5),
    surfaceVariant = Color(0xFF262C42),
    onSurfaceVariant = Color(0xFFAAB2C8),
    surfaceContainerLowest = Color(0xFF0A0D18),
    surfaceContainerLow = Color(0xFF151A2D),
    surfaceContainer = Color(0xFF191F34),
    surfaceContainerHigh = Color(0xFF20263D),
    surfaceContainerHighest = Color(0xFF282F48),
    outline = Color(0xFF4A526B),
    outlineVariant = Color(0xFF30374F),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF5F1410),
    onErrorContainer = Color(0xFFFFE1DE)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun SegnaOreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = AppShapes,
        content = content
    )
}

/** Card bianca, arrotondata e leggermente in rilievo. */
@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        content = content
    )
}
