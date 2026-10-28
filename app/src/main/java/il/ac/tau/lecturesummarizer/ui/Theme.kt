package il.ac.tau.lecturesummarizer.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF2B5BB5), onPrimary = Color.White, primaryContainer = Color(0xFFDCE6FF), onPrimaryContainer = Color(0xFF0B1E47),
    secondary = Color(0xFF5B6378), tertiary = Color(0xFFB4651C),
    background = Color(0xFFF7F7FA), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFEDEEF3), outlineVariant = Color(0xFFDADCE3),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFAFC6FF), primaryContainer = Color(0xFF1F3F7D), secondary = Color(0xFFC2C7DB), tertiary = Color(0xFFFFB77A),
    background = Color(0xFF111216), surface = Color(0xFF17181D), surfaceVariant = Color(0xFF24262D),
)

private val base = Typography()
private val AppTypography = base.copy(
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium),
)
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
