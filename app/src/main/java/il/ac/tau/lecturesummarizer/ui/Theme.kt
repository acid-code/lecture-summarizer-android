package il.ac.tau.lecturesummarizer.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Brand: navy + yellow accent (ui-language.md). Course tints below stay dynamic.
private val Light = lightColorScheme(
    primary = Color(0xFF1E3A8A), onPrimary = Color.White, primaryContainer = Color(0xFFDCE3F7), onPrimaryContainer = Color(0xFF0F1F4D),
    secondary = Color(0xFF52618A), onSecondary = Color.White, secondaryContainer = Color(0xFFFFEFB8), onSecondaryContainer = Color(0xFF0F1F4D),
    tertiary = Color(0xFFF2B705), onTertiary = Color(0xFF0F1F4D), tertiaryContainer = Color(0xFFFFEFB8), onTertiaryContainer = Color(0xFF0F1F4D),
    background = Color(0xFFF7F7FA), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFEDEEF3), outlineVariant = Color(0xFFDADCE3),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFAFC2FF), onPrimary = Color(0xFF0F1F4D), primaryContainer = Color(0xFF1E3A8A), onPrimaryContainer = Color(0xFFDCE3F7),
    secondary = Color(0xFF52618A), onSecondary = Color.White, tertiary = Color(0xFFF2B705), onTertiary = Color(0xFF0F1F4D),
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
    // Brand navy/yellow everywhere (wallpaper dynamic color would hide it on API 31+); per-course tints still come from CourseTheme.
    val scheme = if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}

/** Re-tints the Material scheme with the notebook's cover colour so every course-scoped screen keeps the course theme. */
@Composable
fun CourseTheme(app: il.ac.tau.lecturesummarizer.LectureApp, courseId: String?, content: @Composable () -> Unit) {
    val courses by app.repo.courses.collectAsState()
    val course = courses.firstOrNull { it.id == courseId }
    if (course == null) { content(); return }
    val c = course.coverColorValue()
    val dark = isSystemInDarkTheme()
    val base = MaterialTheme.colorScheme
    // Notebook color accents surfaces/covers, never replaces the brand's primary action/status palette.
    val scheme = if (dark) base.copy(
        secondaryContainer = lerp(c, Color.Black, 0.6f), onSecondaryContainer = lerp(c, Color.White, 0.85f), surfaceTint = c,
    ) else base.copy(
        secondaryContainer = lerp(c, Color.White, 0.85f), onSecondaryContainer = lerp(c, Color.Black, 0.6f), surfaceTint = c,
    )
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
}

@Composable
fun LectureTheme(app: il.ac.tau.lecturesummarizer.LectureApp, lectureId: String, content: @Composable () -> Unit) {
    val lectures by app.repo.lectures.collectAsState()
    CourseTheme(app, lectures.firstOrNull { it.id == lectureId }?.courseId, content)
}
