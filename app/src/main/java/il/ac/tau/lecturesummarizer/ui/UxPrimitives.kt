package il.ac.tau.lecturesummarizer.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import il.ac.tau.lecturesummarizer.R

@Composable fun SkeletonLoader(rows:Int=3,modifier:Modifier=Modifier){
 val animation=rememberInfiniteTransition(label="skeleton")
 val alpha by animation.animateFloat(.08f,.18f,infiniteRepeatable(tween(850),RepeatMode.Reverse),label="skeleton-alpha")
 val label=stringResource(R.string.skeleton_loading)
 Column(modifier.fillMaxWidth().semantics{contentDescription=label},verticalArrangement=Arrangement.spacedBy(12.dp)){
  repeat(rows){i->Box(Modifier.fillMaxWidth(if(i==0).65f else 1f).height(if(i==0)20.dp else 64.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha=alpha),RoundedCornerShape(12.dp)))}
 }
}
@Composable internal fun hapticAction(action:()->Unit):()->Unit{
 val haptic=LocalHapticFeedback.current
 return {haptic.performHapticFeedback(HapticFeedbackType.LongPress);action()}
}
