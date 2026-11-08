package il.ac.tau.lecturesummarizer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp

/** Only Home/calendar journeys use this scale. Respect device fontScale; never shrink it away. */
@Composable fun CompactCalendarTypography(content:@Composable ()->Unit){
 val t=MaterialTheme.typography
 // Stable typography identity so nested wrappers don't reshuffle MaterialTheme every recomposition.
 val compact=remember(t){t.copy(
  displaySmall=t.displaySmall.copy(fontSize=18.sp,lineHeight=24.sp),
  headlineSmall=t.headlineSmall.copy(fontSize=18.sp,lineHeight=24.sp),
  titleLarge=t.titleLarge.copy(fontSize=16.sp,lineHeight=22.sp),
  titleMedium=t.titleMedium.copy(fontSize=16.sp,lineHeight=22.sp),
  titleSmall=t.titleSmall.copy(fontSize=14.sp,lineHeight=20.sp),
  bodyLarge=t.bodyLarge.copy(fontSize=14.sp,lineHeight=20.sp),
  bodyMedium=t.bodyMedium.copy(fontSize=14.sp,lineHeight=20.sp),
  bodySmall=t.bodySmall.copy(fontSize=12.sp,lineHeight=16.sp),
  labelLarge=t.labelLarge.copy(fontSize=14.sp,lineHeight=20.sp),
  labelMedium=t.labelMedium.copy(fontSize=12.sp,lineHeight=16.sp),
  labelSmall=t.labelSmall.copy(fontSize=12.sp,lineHeight=16.sp)
 )}
 MaterialTheme(typography=compact,content=content)
}
