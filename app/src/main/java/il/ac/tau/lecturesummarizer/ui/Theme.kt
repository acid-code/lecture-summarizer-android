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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import il.ac.tau.lecturesummarizer.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Brand: navy + yellow accent (ui-language.md). Course tints below stay dynamic.
private val Light = lightColorScheme(
    primary = Color(0xFF1E3A8A), onPrimary = Color.White, primaryContainer = Color(0xFFDCE3F7), onPrimaryContainer = Color(0xFF0F1F4D),
    secondary = Color(0xFF52618A), onSecondary = Color.White, secondaryContainer = Color(0xFFDCE3F7), onSecondaryContainer = Color(0xFF0F1F4D),
    tertiary = Color(0xFFF2B705), onTertiary = Color(0xFF0F1F4D), tertiaryContainer = Color(0xFFFFEFB8), onTertiaryContainer = Color(0xFF0F1F4D),
    surfaceContainerLow=Color(0xFFF5F6FA),surfaceContainer=Color.White,surfaceContainerHigh=Color(0xFFEDEEF3),background = Color(0xFFF5F6FA), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFEDEEF3), outlineVariant = Color(0xFFE6E8F0),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFAFC2FF), onPrimary = Color(0xFF0F1F4D), primaryContainer = Color(0xFF1E3A8A), onPrimaryContainer = Color(0xFFDCE3F7),
    secondary = Color(0xFF52618A), onSecondary = Color.White, tertiary = Color(0xFFF2B705), onTertiary = Color(0xFF0F1F4D),
    secondaryContainer=Color(0xFF26375F),onSecondaryContainer=Color(0xFFDCE3F7),surfaceContainerLow=Color(0xFF161B2E),surfaceContainer=Color(0xFF161B2E),surfaceContainerHigh=Color(0xFF252B42),outlineVariant=Color(0xFF252B42),background = Color(0xFF0E1222), surface = Color(0xFF161B2E), surfaceVariant = Color(0xFF252B42),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val Rubik=FontFamily(*listOf(FontWeight.Normal,FontWeight.Medium,FontWeight.SemiBold,FontWeight.Bold).map{weight->Font(R.font.rubik,weight=weight,variationSettings=androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(weight.weight)))}.toTypedArray())
private val base=Typography()
private val AppTypography=base.copy(
 displaySmall=base.displaySmall.copy(fontFamily=Rubik,fontSize=28.sp,lineHeight=34.sp,fontWeight=FontWeight.Bold),
 headlineSmall=base.headlineSmall.copy(fontFamily=Rubik,fontSize=28.sp,lineHeight=34.sp,fontWeight=FontWeight.Bold),
 titleLarge=base.titleLarge.copy(fontFamily=Rubik,fontSize=20.sp,lineHeight=26.sp,fontWeight=FontWeight.SemiBold),
 titleMedium=base.titleMedium.copy(fontFamily=Rubik,fontSize=20.sp,lineHeight=26.sp,fontWeight=FontWeight.SemiBold),
 titleSmall=base.titleSmall.copy(fontFamily=Rubik,fontSize=15.sp,lineHeight=22.sp,fontWeight=FontWeight.SemiBold),
 bodyLarge=base.bodyLarge.copy(fontFamily=Rubik,fontSize=15.sp,lineHeight=22.sp),
 bodyMedium=base.bodyMedium.copy(fontFamily=Rubik,fontSize=15.sp,lineHeight=22.sp),
 bodySmall=base.bodySmall.copy(fontFamily=Rubik,fontSize=12.sp,lineHeight=16.sp),
 labelLarge=base.labelLarge.copy(fontFamily=Rubik,fontSize=15.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium),
 labelMedium=base.labelMedium.copy(fontFamily=Rubik,fontSize=12.sp,lineHeight=16.sp,fontWeight=FontWeight.Medium),
 labelSmall=base.labelSmall.copy(fontFamily=Rubik,fontSize=12.sp,lineHeight=16.sp,fontWeight=FontWeight.Medium))
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AppTheme(darkMode:Boolean?=null, content: @Composable () -> Unit) {
    val dark = darkMode ?: isSystemInDarkTheme()
    // Brand navy/yellow everywhere (wallpaper dynamic color would hide it on API 31+); per-course tints still come from CourseTheme.
    val scheme = if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}

/** Course colours belong to cover bands/dots, never selected states or action buttons. */
@Composable
fun CourseTheme(app: il.ac.tau.lecturesummarizer.LectureApp, courseId: String?, content: @Composable () -> Unit) {
    content()
}

@Composable
fun LectureTheme(app: il.ac.tau.lecturesummarizer.LectureApp, lectureId: String, content: @Composable () -> Unit) {
    val lectures by app.repo.lectures.collectAsState()
    CourseTheme(app, lectures.firstOrNull { it.id == lectureId }?.courseId, content)
}
