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

/** Re-tints the Material scheme with the notebook's cover colour so every course-scoped screen keeps the course theme. */
@Composable
fun CourseTheme(app: il.ac.tau.lecturesummarizer.LectureApp, courseId: String?, content: @Composable () -> Unit) {
    val courses by app.repo.courses.collectAsState()
    val course = courses.firstOrNull { it.id == courseId }
    if (course == null) { content(); return }
    val c = course.coverColorValue()
    val dark = isSystemInDarkTheme()
    val base = MaterialTheme.colorScheme
    val onC = if (c.luminance() > 0.5f) Color(0xFF1B1B1F) else Color.White
    val scheme = if (dark) base.copy(
        primary = lerp(c, Color.White, 0.35f), onPrimary = Color(0xFF111216),
        primaryContainer = lerp(c, Color.Black, 0.45f), onPrimaryContainer = lerp(c, Color.White, 0.85f),
        secondaryContainer = lerp(c, Color.Black, 0.6f), surfaceTint = c,
    ) else base.copy(
        primary = c, onPrimary = onC,
        primaryContainer = lerp(c, Color.White, 0.8f), onPrimaryContainer = lerp(c, Color.Black, 0.6f),
        secondaryContainer = lerp(c, Color.White, 0.85f), onSecondaryContainer = lerp(c, Color.Black, 0.6f), surfaceTint = c,
    )
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
}

@Composable
fun LectureTheme(app: il.ac.tau.lecturesummarizer.LectureApp, lectureId: String, content: @Composable () -> Unit) {
    val lectures by app.repo.lectures.collectAsState()
    CourseTheme(app, lectures.firstOrNull { it.id == lectureId }?.courseId, content)
}
